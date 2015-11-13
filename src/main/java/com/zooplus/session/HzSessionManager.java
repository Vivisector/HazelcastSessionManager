package com.zooplus.session;

import com.hazelcast.client.HazelcastClient;
import com.hazelcast.client.config.ClientConfig;
import com.hazelcast.client.config.XmlClientConfigBuilder;
import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.core.IMap;
import org.apache.catalina.Container;
import org.apache.catalina.Lifecycle;
import org.apache.catalina.LifecycleException;
import org.apache.catalina.LifecycleListener;
import org.apache.catalina.Loader;
import org.apache.catalina.Manager;
import org.apache.catalina.Session;
import org.apache.catalina.Valve;
import org.apache.catalina.session.StandardSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.beans.PropertyChangeListener;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Created by Igor Ivaniuk on 13.11.2015.
 */
public class HzSessionManager implements Manager, Lifecycle {

    private static final Logger log = LoggerFactory.getLogger(HzSessionManager.class)

    private Container container;
    private int maxInactiveInterval;
    private ThreadLocal<StandardSession> currentSession = new ThreadLocal<StandardSession>();
    private Serializer serializer;

    private IMap<Object, Object> sessions = HazelcastInstance

    //Either 'kryo' or 'java'
    private String serializationStrategyClass = "com.zooplus.session.JavaSerializer";

    public static String getHost() {
        return host;
    }

    public static void setHost(String host) {
        MongoManager.host = host;
    }

    public static int getPort() {
        return port;
    }

    public static void setPort(int port) {
        MongoManager.port = port;
    }

    public static String getDatabase() {
        return database;
    }

    public static void setDatabase(String database) {
        MongoManager.database = database;
    }

    @Override
    public Container getContainer() {
        return container;
    }

    @Override
    public void setContainer(Container container) {
        this.container = container;
    }

    @Override
    public boolean getDistributable() {
        return false;
    }

    @Override
    public void setDistributable(boolean b) {

    }

    @Override
    public String getInfo() {
        return "Hazelcast Session Manager";
    }

    @Override
    public int getMaxInactiveInterval() {
        return maxInactiveInterval;
    }

    @Override
    public void setMaxInactiveInterval(int i) {
        maxInactiveInterval = i;
    }

    @Override
    public int getSessionIdLength() {
        return 37;
    }

    @Override
    public void setSessionIdLength(int i) {

    }

    @Override
    public int getSessionCounter() {
        return 10000000;
    }

    @Override
    public void setSessionCounter(int i) {

    }

    @Override
    public int getMaxActive() {
        return 1000000;
    }

    @Override
    public void setMaxActive(int i) {

    }

    @Override
    public int getActiveSessions() {
        return 1000000;
    }

    @Override
    public int getExpiredSessions() {
        return 0;
    }

    @Override
    public void setExpiredSessions(int i) {

    }

    public int getRejectedSessions() {
        return 0;
    }

    public void setRejectedSessions(int i) {
    }

    public void setSerializationStrategyClass(String strategy) {
        this.serializationStrategyClass = strategy;
    }

    @Override
    public int getSessionMaxAliveTime() {
        return maxInactiveInterval;
    }

    @Override
    public void setSessionMaxAliveTime(int i) {

    }

    @Override
    public int getSessionAverageAliveTime() {
        return 0;
    }

    @Override
    public void setSessionAverageAliveTime(int i) {

    }

    public void load() throws ClassNotFoundException, IOException {
    }

    public void unload() throws IOException {
    }

    @Override
    public void backgroundProcess() {
        processExpires();
    }

    public void addLifecycleListener(LifecycleListener lifecycleListener) {
    }

    public LifecycleListener[] findLifecycleListeners() {
        return new LifecycleListener[0];  //To change body of implemented methods use File | Settings | File Templates.
    }

    public void removeLifecycleListener(LifecycleListener lifecycleListener) {
    }

    @Override
    public void add(Session session) {
        try {
            save(session);
        } catch (IOException ex) {
            log.error("Error adding new session", ex);
        }
    }

    @Override
    public void addPropertyChangeListener(PropertyChangeListener propertyChangeListener) {
        //To change body of implemented methods use File | Settings | File Templates.
    }

    @Override
    public void changeSessionId(Session session) {
        session.setId(UUID.randomUUID().toString());
    }

    @Override
    public Session createEmptySession() {
        HzSession session = new HzSession(this);
        session.setId(UUID.randomUUID().toString());
        session.setMaxInactiveInterval(maxInactiveInterval);
        session.setValid(true);
        session.setCreationTime(System.currentTimeMillis());
        session.setNew(true);
        currentSession.set(session);
        log.debug("Created new empty session " + session.getIdInternal());
        return session;
    }

    /**
     * @deprecated
     */
    public org.apache.catalina.Session createSession() {
        return createEmptySession();
    }

    public org.apache.catalina.Session createSession(java.lang.String sessionId) {
        StandardSession session = (HzSession) createEmptySession();

        log.debug("Created session with id " + session.getIdInternal() + " ( " + sessionId + ")");
        if (sessionId != null) {
            session.setId(sessionId);
        }

        return session;
    }

    public org.apache.catalina.Session[] findSessions() {
        try {
            List<Session> sessions = new ArrayList<Session>();
            for (String sessionId : keys()) {
                sessions.add(loadSession(sessionId));
            }
            return sessions.toArray(new Session[sessions.size()]);
        } catch (IOException ex) {
            throw new RuntimeException(ex);
        }
    }

    protected org.apache.catalina.session.StandardSession getNewSession() {
        log.debug("getNewSession()");
        return (MongoSession) createEmptySession();
    }

    public void start() throws LifecycleException {
        for (Valve valve : getContainer().getPipeline().getValves()) {
            if (valve instanceof MongoSessionTrackerValve) {
                trackerValve = (MongoSessionTrackerValve) valve;
                trackerValve.setMongoManager(this);
                log.info("Attached to Mongo Tracker Valve");
                break;
            }
        }
        try {
            initSerializer();
        } catch (ClassNotFoundException e) {
            log.error(Level.SEVERE, "Unable to load serializer", e);
            throw new LifecycleException(e);
        } catch (InstantiationException e) {
            log.error("Unable to load serializer", e);
            throw new LifecycleException(e);
        } catch (IllegalAccessException e) {
            log.error("Unable to load serializer", e);
            throw new LifecycleException(e);
        }
        log.info("Will expire sessions after " + getMaxInactiveInterval() + " seconds");
        initDbConnection();
    }

    public void stop() throws LifecycleException {
        mongo.close();
    }

    public Session findSession(String id) throws IOException {
        return loadSession(id);
    }

    public void clear() throws IOException {
        getCollection().drop();
        getCollection().ensureIndex(new BasicDBObject("lastmodified", 1));
    }

    private DBCollection getCollection() throws IOException {
        return db.getCollection("sessions");
    }

    public int getSize() throws IOException {
        return (int) getCollection().count();
    }

    public String[] keys() throws IOException {

        BasicDBObject restrict = new BasicDBObject();
        restrict.put("_id", 1);

        DBCursor cursor = getCollection().find(new BasicDBObject(), restrict);

        List<String> ret = new ArrayList<String>();

        while (cursor.hasNext()) {
            ret.add(cursor.next().get("").toString());
        }

        return ret.toArray(new String[ret.size()]);
    }


    public Session loadSession(String id) throws IOException {

        if (id == null || id.length() == 0) {
            return createEmptySession();
        }

        StandardSession session = currentSession.get();

        if (session != null) {
            if (id.equals(session.getId())) {
                return session;
            } else {
                currentSession.remove();
            }
        }
        try {
            log.fine("Loading session " + id + " from Mongo");
            BasicDBObject query = new BasicDBObject();
            query.put("_id", id);

            DBObject dbsession = getCollection().findOne(query);

            if (dbsession == null) {
                log.fine("Session " + id + " not found in Mongo");
                StandardSession ret = getNewSession();
                ret.setId(id);
                currentSession.set(ret);
                return ret;
            }

            byte[] data = (byte[]) dbsession.get("data");

            session = (MongoSession) createEmptySession();
            session.setId(id);
            session.setManager(this);
            serializer.deserializeInto(data, session);

            session.setMaxInactiveInterval(-1);
            session.access();
            session.setValid(true);
            session.setNew(false);

            if (log.isLoggable(Level.FINE)) {
                log.fine("Session Contents [" + session.getId() + "]:");
                for (Object name : Collections.list(session.getAttributeNames())) {
                    log.fine("  " + name);
                }
            }

            log.fine("Loaded session id " + id);
            currentSession.set(session);
            return session;
        } catch (IOException e) {
            log.severe(e.getMessage());
            throw e;
        } catch (ClassNotFoundException ex) {
            log.error("Unable to deserialize session ", ex);
            throw new IOException("Unable to deserializeInto session", ex);
        }
    }

    public void save(Session session) throws IOException {
        try {
            log.fine("Saving session " + session + " into Mongo");

            StandardSession standardsession = (MongoSession) session;

            if (log.isLoggable(Level.FINE)) {
                log.fine("Session Contents [" + session.getId() + "]:");
                for (Object name : Collections.list(standardsession.getAttributeNames())) {
                    log.fine("  " + name);
                }
            }

            byte[] data = serializer.serializeFrom(standardsession);

            BasicDBObject dbsession = new BasicDBObject();
            dbsession.put("_id", standardsession.getId());
            dbsession.put("data", data);
            dbsession.put("lastmodified", System.currentTimeMillis());

            BasicDBObject query = new BasicDBObject();
            query.put("_id", standardsession.getIdInternal());
            getCollection().update(query, dbsession, true, false);
            log.fine("Updated session with id " + session.getIdInternal());
        } catch (IOException e) {
            log.severe(e.getMessage());
            e.printStackTrace();
            throw e;
        } finally {
            currentSession.remove();
            log.fine("Session removed from ThreadLocal :" + session.getIdInternal());
        }
    }

    public void remove(Session session) {
        log.fine("Removing session ID : " + session.getId());
        BasicDBObject query = new BasicDBObject();
        query.put("_id", session.getId());

        try {
            getCollection().remove(query);
        } catch (IOException e) {
            log.error("Error removing session in Mongo Session Store", e);
        } finally {
            currentSession.remove();
        }
    }

    @Override
    public void removePropertyChangeListener(PropertyChangeListener propertyChangeListener) {
        //To change body of implemented methods use File | Settings | File Templates.
    }

    public void processExpires() {
        BasicDBObject query = new BasicDBObject();

        long olderThan = System.currentTimeMillis() - (getMaxInactiveInterval() * 1000);

        log.fine("Looking for sessions less than for expiry in Mongo : " + olderThan);

        query.put("lastmodified", new BasicDBObject("$lt", olderThan));

        try {
            WriteResult result = getCollection().remove(query);
            log.fine("Expired sessions : " + result.getN());
        } catch (IOException e) {
            log.error("Error cleaning session in Mongo Session Store", e);
        }
    }

    private void initHazelcastMap() throws LifecycleException {
        try {
            ClientConfig clientConfig = new XmlClientConfigBuilder("hazelcast-client.xml").build();
            HazelcastInstance hazelcastClient = HazelcastClient.newHazelcastClient(clientConfig);
            Map<String, String> bigMap = null;
            sessions = hazelcastClient.getMap("sessions");
        } catch (IOException e) {
            e.printStackTrace();
            throw new LifecycleException("Error Connecting to Mongo", e);
        }
    }

    private void initSerializer() throws ClassNotFoundException, IllegalAccessException, InstantiationException {
        log.info("Attempting to use serializer :" + serializationStrategyClass);
        serializer = (Serializer) Class.forName(serializationStrategyClass).newInstance();

        Loader loader = null;

        if (container != null) {
            loader = container.getLoader();
        }
        ClassLoader classLoader = null;

        if (loader != null) {
            classLoader = loader.getClassLoader();
        }
        serializer.setClassLoader(classLoader);
    }

}


