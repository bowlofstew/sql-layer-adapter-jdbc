package org.postgresql;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.security.AccessController;
import java.security.PrivilegedActionException;
import java.security.PrivilegedExceptionAction;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.DriverPropertyInfo;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.Properties;
import java.util.Timer;
import java.util.TimerTask;

import org.postgresql.core.Logger;
import org.postgresql.util.GT;
import org.postgresql.util.HostSpec;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;

public abstract class DriverBase implements Driver {

    // make these public so they can be used in setLogLevel below

    public static final int DEBUG = 2;
    public static final int INFO = 1;
    public static final int OFF = 0;
    
    private static final Logger logger = new Logger();
    private static boolean logLevelSet = false;
    private static Timer cancelTimer=null;
    // Helper to retrieve default properties from classloader resource
    // properties files.
    private Properties defaultProperties;

    private Properties loadDefaultProperties() throws IOException {
        Properties merged = new Properties();

        try {
            merged.setProperty("user", System.getProperty("user.name"));
        } catch (java.lang.SecurityException se) {
            // We're just trying to set a default, so if we can't
            // it's not a big deal.
        }

        // If we are loaded by the bootstrap classloader, getClassLoader()
        // may return null. In that case, try to fall back to the system
        // classloader.
        //
        // We should not need to catch SecurityException here as we are
        // accessing either our own classloader, or the system classloader
        // when our classloader is null. The ClassLoader javadoc claims
        // neither case can throw SecurityException.
        ClassLoader cl = getClass().getClassLoader();
        if (cl == null)
            cl = ClassLoader.getSystemClassLoader();

        if (cl == null) {
            logger.debug("Can't find a classloader for the Driver; not loading driver configuration");
            return merged; // Give up on finding defaults.
        }

        logger.debug("Loading driver configuration via classloader " + cl);

        // When loading the driver config files we don't want settings found
        // in later files in the classpath to override settings specified in
        // earlier files.  To do this we've got to read the returned
        // Enumeration into temporary storage.
        ArrayList urls = new ArrayList();
        Enumeration urlEnum = cl.getResources(getResourcesName());
        while (urlEnum.hasMoreElements())
        {
            urls.add(urlEnum.nextElement());
        }

        for (int i=urls.size()-1; i>=0; i--) {
            URL url = (URL)urls.get(i);
            logger.debug("Loading driver configuration from: " + url);
            InputStream is = url.openStream();
            merged.load(is);
            is.close();
        }

        return merged;
    }
    
    protected abstract String getResourcesName();
    /**
     * Try to make a database connection to the given URL. The driver
     * should return "null" if it realizes it is the wrong kind of
     * driver to connect to the given URL. This will be common, as
     * when the JDBC driverManager is asked to connect to a given URL,
     * it passes the URL to each loaded driver in turn.
     *
     * <p>The driver should raise an SQLException if it is the right driver
     * to connect to the given URL, but has trouble connecting to the
     * database.
     *
     * <p>The java.util.Properties argument can be used to pass arbitrary
     * string tag/value pairs as connection arguments.
     *
     * user - (required) The user to connect as
     * password - (optional) The password for the user
     * ssl - (optional) Use SSL when connecting to the server
     * readOnly - (optional) Set connection to read-only by default
     * charSet - (optional) The character set to be used for converting
     *  to/from the database to unicode.  If multibyte is enabled on the
     *  server then the character set of the database is used as the default,
     *  otherwise the jvm character encoding is used as the default.
     *   This value is only used when connecting to a 7.2 or older server.
     * loglevel - (optional) Enable logging of messages from the driver.
     *  The value is an integer from 0 to 2 where:
     *    OFF = 0, INFO = 1, DEBUG = 2
     *  The output is sent to DriverManager.getPrintWriter() if set,
     *  otherwise it is sent to System.out.
     * compatible - (optional) This is used to toggle
     *  between different functionality as it changes across different releases
     *  of the jdbc driver code.  The values here are versions of the jdbc
     *  client and not server versions.  For example in 7.1 get/setBytes
     *  worked on LargeObject values, in 7.2 these methods were changed
     *  to work on bytea values.  This change in functionality could
     *  be disabled by setting the compatible level to be "7.1", in
     *  which case the driver will revert to the 7.1 functionality.
     *
     * <p>Normally, at least
     * "user" and "password" properties should be included in the
     * properties. For a list of supported
     * character encoding , see
     * http://java.sun.com/products/jdk/1.2/docs/guide/internat/encoding.doc.html
     * Note that you will probably want to have set up the Postgres database
     * itself to use the same encoding, with the "-E <encoding>" argument
     * to createdb.
     *
     * Our protocol takes the forms:
     * <PRE>
     * jdbc:postgresql://host:port/database?param1=val1&...
     * </PRE>
     *
     * @param url the URL of the database to connect to
     * @param info a list of arbitrary tag/value pairs as connection
     * arguments
     * @return a connection to the URL or null if it isnt us
     * @exception SQLException if a database access error occurs
     * @see java.sql.Driver#connect
     */
    public java.sql.Connection connect(String url, Properties info) throws SQLException
    {
        // get defaults
        Properties defaults;

        if (!url.startsWith(getProtocol())) {
            return null;
        }
        try
        {
            defaults = getDefaultProperties();
        }
        catch (IOException ioe)
        {
            throw new PSQLException(GT.tr("Error loading default settings from driverconfig.properties"),
                                    PSQLState.UNEXPECTED_ERROR, ioe);
        }

        // override defaults with provided properties
        Properties props = new Properties(defaults);
        if (info != null)
        {
            for (Enumeration e = info.propertyNames(); e.hasMoreElements(); )
            {
                String propName = (String)e.nextElement();
                String propValue = info.getProperty(propName);
                if ( propValue == null ) {
                    throw new PSQLException(GT.tr("Properties for the driver contains a non-string value for the key ")+propName,
                                            PSQLState.UNEXPECTED_ERROR);
                }
                props.setProperty( propName,propValue );
            }
        }
        
        // parse URL and add more properties
        if ((props = parseURL(url, props)) == null)
        {
            logger.debug("Error in url: " + url);
            return null;
        }
        try
        {
            logger.debug("Connecting with URL: " + url);

            // Enforce login timeout, if specified, by running the connection
            // attempt in a separate thread. If we hit the timeout without the
            // connection completing, we abandon the connection attempt in
            // the calling thread, but the separate thread will keep trying.
            // Eventually, the separate thread will either fail or complete
            // the connection; at that point we clean up the connection if
            // we managed to establish one after all. See ConnectThread for
            // more details.
            long timeout = timeout(props);
            if (timeout <= 0)
                return makeConnection(url, props);

            ConnectThread ct = new ConnectThread(makeConnection(url, props));
            new Thread(ct, getName() + " JDBC driver connection thread").start();
            return ct.getResult(timeout);
        }
        catch (PSQLException ex1)
        {
            logger.debug("Connection error:", ex1);
            // re-throw the exception, otherwise it will be caught next, and a
            // org.postgresql.unusual error will be returned instead.
            throw ex1;
        }
        catch (java.security.AccessControlException ace)
        {
            throw new PSQLException(GT.tr("Your security policy has prevented the connection from being attempted.  You probably need to grant the connect java.net.SocketPermission to the database server host and port that you wish to connect to."), PSQLState.UNEXPECTED_ERROR, ace);
        }
        catch (Exception ex2)
        {
            logger.debug("Unexpected connection error:", ex2);
            throw new PSQLException(GT.tr("Something unusual has occurred to cause the driver to fail. Please report this exception."),
                                    PSQLState.UNEXPECTED_ERROR, ex2);
        }
    }

    protected abstract String getName();
    /**
     * Perform a connect in a separate thread; supports
     * getting the results from the original thread while enforcing
     * a login timout.
     */
    private static class ConnectThread implements Runnable {
        ConnectThread(Connection conn) {
            this.newConn = conn;
        }

        public void run() {
            Connection conn;
            Throwable error;

            try {
                conn = newConn;
                error = null;
            } catch (Throwable t) {
                conn = null;
                error = t;
            }

            synchronized (this) {
                if (abandoned) {
                    if (conn != null) {
                        try {
                            conn.close();
                        } catch (SQLException e) {}
                    }
                } else {
                    result = conn;
                    resultException = error;
                    notify();
                }
            }
        }

        /**
         * Get the connection result from this (assumed running) thread.
         * If the timeout is reached without a result being available,
         * a SQLException is thrown.
         *
         * @param timeout timeout in milliseconds
         * @return the new connection, if successful
         * @throws SQLException if a connection error occurs or the timeout is reached
         */
        public Connection getResult(long timeout) throws SQLException {
            long expiry = System.currentTimeMillis() + timeout;
            synchronized (this) {
                while (true) {
                    if (result != null)
                        return result;
                    
                    if (resultException != null) {
                        if (resultException instanceof SQLException) {
                            resultException.fillInStackTrace();
                            throw (SQLException)resultException;
                        } else {
                            throw new PSQLException(GT.tr("Something unusual has occurred to cause the driver to fail. Please report this exception."),
                                                    PSQLState.UNEXPECTED_ERROR, resultException);
                        }
                    }
                    
                    long delay = expiry - System.currentTimeMillis();
                    if (delay <= 0) {
                        abandoned = true;
                        throw new PSQLException(GT.tr("Connection attempt timed out."),
                                                PSQLState.CONNECTION_UNABLE_TO_CONNECT);
                    }
                    
                    try {
                        wait(delay);
                    } catch (InterruptedException ie) {
            
            // reset the interrupt flag                         
            Thread.currentThread().interrupt();
            abandoned = true;
            
            // throw an unchecked exception which will hopefully not be ignored by the calling code
            throw new RuntimeException(GT.tr("Interrupted while attempting to connect."));     
           }                                            
                }
            }
        }

        private Connection result;
        private Connection newConn;
        private Throwable resultException;
        private boolean abandoned;
    }

    /**
     * Create a connection from URL and properties. Always
     * does the connection work in the current thread without
     * enforcing a timeout, regardless of any timeout specified
     * in the properties.
     *
     * @param url the original URL
     * @param props the parsed/defaulted connection properties
     * @return a new connection
     * @throws SQLException if the connection could not be made
     */
    protected abstract Connection makeConnection(String url, Properties props) throws SQLException;

    /**
     * Returns true if the driver thinks it can open a connection to the
     * given URL.  Typically, drivers will return true if they understand
     * the subprotocol specified in the URL and false if they don't.  Our
     * protocols start with jdbc:postgresql:
     *
     * @see java.sql.Driver#acceptsURL
     * @param url the URL of the driver
     * @return true if this driver accepts the given URL
     * @exception SQLException if a database-access error occurs
     * (Dont know why it would *shrug*)
     */
    public boolean acceptsURL(String url) throws SQLException
    {
        if (parseURL(url, null) == null)
            return false;
        return true;
    }

    private static final Object[][] knownProperties = {
                { "PGDBNAME", Boolean.TRUE,
                  "Database name to connect to; may be specified directly in the JDBC URL." },
                { "user", Boolean.TRUE,
                  "Username to connect to the database as.", null },
                { "PGHOST", Boolean.FALSE,
                  "Hostname of the PostgreSQL server; may be specified directly in the JDBC URL." },
                { "PGPORT", Boolean.FALSE,
                  "Port number to connect to the PostgreSQL server on; may be specified directly in the JDBC URL.", },
                { "password", Boolean.FALSE,
                  "Password to use when authenticating.", },
                { "protocolVersion", Boolean.FALSE,
                  "Force use of a particular protocol version when connecting; if set, disables protocol version fallback.", },
                { "ssl", Boolean.FALSE,
                  "Control use of SSL; any nonnull value causes SSL to be required." },
                { "sslfactory", Boolean.FALSE,
                  "Provide a SSLSocketFactory class when using SSL." },
                { "sslfactoryarg", Boolean.FALSE,
                  "Argument forwarded to constructor of SSLSocketFactory class." },
                { "loglevel", Boolean.FALSE,
                  "Control the driver's log verbosity: 0 is OFF, 1 is INFO, 2 is DEBUG.",
                  new String[] { "0", "1", "2" } },
                { "allowEncodingChanges", Boolean.FALSE,
                  "Allow the user to change the client_encoding variable." },
                { "logUnclosedConnections", Boolean.FALSE,
                  "When connections that are not explicitly closed are garbage collected, log the stacktrace from the opening of the connection to trace the leak source."},
                { "prepareThreshold", Boolean.FALSE,
                  "Default statement prepare threshold (numeric)." },
                { "binaryTransfer", Boolean.FALSE,
                  "Use binary format for sending and receiving data if possible." },
                { "binaryTransferEnable", Boolean.FALSE,
                  "Comma separated list of types to enable binary transfer. Either OID numbers or names." },
                { "binaryTransferDisable", Boolean.FALSE,
                  "Comma separated list of types to disable binary transfer. Either OID numbers or names. Overrides values in the driver default set and values set with binaryTransferEnable." },
                { "charSet", Boolean.FALSE,
                  "When connecting to a pre-7.3 server, the database encoding to assume is in use." },
                { "compatible", Boolean.FALSE,
                  "Force compatibility of some features with an older version of the driver.",
                  new String[] { "7.1", "7.2", "7.3", "7.4", "8.0", "8.1", "8.2" } },
                { "loginTimeout", Boolean.FALSE,
                  "The login timeout, in seconds; 0 means no timeout beyond the normal TCP connection timout." },
                { "socketTimeout", Boolean.FALSE,
                  "The timeout value for socket read operations, in seconds; 0 means no timeout." },
                { "tcpKeepAlive", Boolean.FALSE,
                  "Enable or disable TCP keep-alive probe." },
                { "stringtype", Boolean.FALSE,
                  "The type to bind String parameters as (usually 'varchar'; 'unspecified' allows implicit casting to other types)",
                  new String[] { "varchar", "unspecified" } },
                { "kerberosServerName", Boolean.FALSE,
                  "The Kerberos service name to use when authenticating with GSSAPI.  This is equivalent to libpq's PGKRBSRVNAME environment variable." },
                { "jaasApplicationName", Boolean.FALSE,
                  "Specifies the name of the JAAS system or application login configuration." }
            };

    /**
     * The getPropertyInfo method is intended to allow a generic GUI
     * tool to discover what properties it should prompt a human for
     * in order to get enough information to connect to a database.
     *
     * <p>Note that depending on the values the human has supplied so
     * far, additional values may become necessary, so it may be necessary
     * to iterate through several calls to getPropertyInfo
     *
     * @param url the Url of the database to connect to
     * @param info a proposed list of tag/value pairs that will be sent on
     * connect open.
     * @return An array of DriverPropertyInfo objects describing
     * possible properties.  This array may be an empty array if
     * no properties are required
     * @exception SQLException if a database-access error occurs
     * @see java.sql.Driver#getPropertyInfo
     */
    public DriverPropertyInfo[] getPropertyInfo(String url, Properties info) throws SQLException
    {
        Properties copy = new Properties(info);
        copy = parseURL(url, copy);

        DriverPropertyInfo[] props = new DriverPropertyInfo[knownProperties.length];
        for (int i = 0; i < knownProperties.length; ++i)
        {
            String name = (String) knownProperties[i][0];
            props[i] = new DriverPropertyInfo(name, copy.getProperty(name));
            props[i].required = ((Boolean) knownProperties[i][1]).booleanValue();
            props[i].description = (String) knownProperties[i][2];
            if (knownProperties[i].length > 3)
                props[i].choices = (String[]) knownProperties[i][3];
        }

        return props;
    }

    /**
     * Gets the drivers major version number
     *
     * @return the drivers major version number
     */
    public abstract int getMajorVersion();


    /**
     * Get the drivers minor version number
     *
     * @return the drivers minor version number
     */
    public abstract int getMinorVersion();
 
    /**
     * Report whether the driver is a genuine JDBC compliant driver.  A
     * driver may only report "true" here if it passes the JDBC compliance
     * tests, otherwise it is required to return false.  JDBC compliance
     * requires full support for the JDBC API and full support for SQL 92
     * Entry Level.
     *
     * <p>For PostgreSQL, this is not yet possible, as we are not SQL92
     * compliant (yet).
     */
    public boolean jdbcCompliant()
    {
        return false;
    }

    public abstract String getProtocol();
    
    /**
     * Constructs a new DriverURL, splitting the specified URL into its
     * component parts
     * @param url JDBC URL to parse
     * @param defaults Default properties
     * @return Properties with elements added from the url
     * @exception SQLException
     */
    public Properties parseURL(String url, Properties defaults) throws SQLException
    {
        Properties urlProps = new Properties(defaults);

        String l_urlServer = url;
        String l_urlArgs = "";

        int l_qPos = url.indexOf('?');
        if (l_qPos != -1)
        {
            l_urlServer = url.substring(0, l_qPos);
            l_urlArgs = url.substring(l_qPos + 1);
        }
        
        if (!l_urlServer.startsWith(getProtocol())) {
            return null;
        }
        l_urlServer = l_urlServer.substring(getProtocol().length());

        if (l_urlServer.startsWith("//")) {
            l_urlServer = l_urlServer.substring(2);
            int slash = l_urlServer.indexOf('/');
            if (slash == -1) {
                return null;
            }
            urlProps.setProperty("PGDBNAME", l_urlServer.substring(slash + 1));

            String[] addresses = l_urlServer.substring(0, slash).split(",");
            StringBuffer hosts = new StringBuffer();
            StringBuffer ports = new StringBuffer();
            for (int addr = 0; addr < addresses.length; ++addr) {
                String address = addresses[addr];
                
                int portIdx = address.lastIndexOf(':');
                if (portIdx != -1 && address.lastIndexOf(']') < portIdx) {
                    String portStr = address.substring(portIdx + 1);
                    try {
                        Integer.parseInt(portStr);
                    } catch (NumberFormatException ex) {
                        return null;
                    }
                    ports.append(portStr);
                    hosts.append(address.subSequence(0, portIdx));
                } else {
                    ports.append(getPortNumber());
                    hosts.append(address);
                }
                ports.append(',');
                hosts.append(',');
            }
            ports.setLength(ports.length() - 1);
            hosts.setLength(hosts.length() - 1);
            urlProps.setProperty("PGPORT", ports.toString());
            urlProps.setProperty("PGHOST", hosts.toString());
        } else {
            urlProps.setProperty("PGPORT", getPortNumber());
            urlProps.setProperty("PGHOST", "localhost");        
            urlProps.setProperty("PGDBNAME", l_urlServer);
        }

        //parse the args part of the url
        String[] args = l_urlArgs.split("&");
        for (int i = 0; i < args.length; ++i)
        {
            String token = args[i];
            if (token.length() ==  0) {
                continue;
            }
            int l_pos = token.indexOf('=');
            if (l_pos == -1)
            {
                urlProps.setProperty(token, "");
            }
            else
            {
                urlProps.setProperty(token.substring(0, l_pos), token.substring(l_pos + 1));
            }
        }

        return urlProps;
    }

    /**
     * @return the address portion of the URL
     */
    protected static HostSpec[] hostSpecs(Properties props)
    {
        String[] hosts = props.getProperty("PGHOST").split(",");
        String[] ports = props.getProperty("PGPORT").split(",");
        HostSpec[] hostSpecs = new HostSpec[hosts.length];
        for (int i = 0; i < hostSpecs.length; ++i) {
            hostSpecs[i] = new HostSpec(hosts[i], Integer.parseInt(ports[i]));
        }
        return hostSpecs;
    }

    /**
     * @return the default configured port number for connection to the database
     */
    protected abstract String getPortNumber();

    /**
     * @return the username of the URL
     */
    protected static String user(Properties props)
    {
        return props.getProperty("user", "");
    }

    /**
     * @return the database name of the URL
     */
    protected static String database(Properties props)
    {
        return props.getProperty("PGDBNAME", "");
    }

    /**
     * @return the timeout from the URL, in milliseconds
     */
    protected static long timeout(Properties props)
    {
        String timeout = props.getProperty("loginTimeout");
        if (timeout != null) {
            try {
                return (long) (Float.parseFloat(timeout) * 1000);
            } catch (NumberFormatException e) {
                // Log level isn't set yet, so this doesn't actually 
                // get printed.
                logger.debug("Couldn't parse loginTimeout value: " + timeout);
            }
        }
        return DriverManager.getLoginTimeout() * 1000;
    }

    /**
    * used to turn logging on to a certain level, can be called
    * by specifying fully qualified class ie org.postgresql.Driver.setLogLevel()
    * @param logLevel sets the level which logging will respond to
    * OFF turn off logging
    * INFO being almost no messages
    * DEBUG most verbose
    */
    public static void setLogLevel(int logLevel)
    {
        synchronized (DriverBase.class) {
            logger.setLogLevel(logLevel);
            logLevelSet = true;
        }
    }

    public static int getLogLevel()
    {
        synchronized (DriverBase.class) {
            return logger.getLogLevel();
        }
    }

    public synchronized  static void addTimerTask(TimerTask timerTask, long milliSeconds)
    {

        if ( cancelTimer == null )
        {
            cancelTimer = new Timer(true);
        }
        cancelTimer.schedule( timerTask, milliSeconds );
    }

    private synchronized Properties getDefaultProperties() throws IOException {
        if (defaultProperties != null)
            return defaultProperties;

        // Make sure we load properties with the maximum possible
        // privileges.
        try
        {
            defaultProperties = (Properties)
                AccessController.doPrivileged(new PrivilegedExceptionAction() {
                        public Object run() throws IOException {
                            return loadDefaultProperties();
                        }
                    });
        }
        catch (PrivilegedActionException e)
        {
            throw (IOException)e.getException();
        }

        // Use the loglevel from the default properties (if any)
        // as the driver-wide default unless someone explicitly called
        // setLogLevel() already.
        synchronized (DriverBase.class) {
            if (!logLevelSet) {
                String driverLogLevel = defaultProperties.getProperty("loglevel");
                if (driverLogLevel != null) {
                    try {
                        setLogLevel(Integer.parseInt(driverLogLevel));
                    } catch (Exception l_e) {
                        // XXX revisit
                        // invalid value for loglevel; ignore it
                    }
                }
            }
        }

        return defaultProperties;
    }
}
