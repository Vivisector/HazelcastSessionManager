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
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Created by Igor Ivaniuk on 13.11.2015.
 */
public class HzSessionManager implements Manager, Lifecycle {

    private static final Logger log = LoggerFactory.getLogger(HzSessionManager.class);

    private Container container;
    private int maxInactiveInterval;
    private ThreadLocal<StandardSession> currentSession = new ThreadLocal<StandardSession>();
    private Serializer serializer;
    private HzSessionTrackerValve trackerValve;
    private HazelcastInstance hazelcastClient;


    private IMap<String, Object> sessions;

    //Either 'kryo' or 'java'
    private String serializationStrategyClass = "com.zooplus.session.JavaSerializer";

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

    public org.apache.catalina.Session createSession(String sessionId) {
        StandardSession session = (HzSession) createEmptySession();

        log.debug("Created session with id " + session.getIdInternal() + " ( " + sessionId + ")");
        if (sessionId != null) {
            session.setId(sessionId);
        }

        return session;
    }

    public org.apache.catalina.Session[] findSessions() {
        try {
            List<Session> sessions = new ArrayList<>();
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
        return (HzSession) createEmptySession();
    }

    public void start() throws LifecycleException {
        for (Valve valve : getContainer().getPipeline().getValves()) {
            if (valve instanceof HzSessionTrackerValve) {
                trackerValve = (HzSessionTrackerValve) valve;
                trackerValve.setManager(this);
                log.info("Attached to Mongo Tracker Valve");
                break;
            }
        }
        try {
            initSerializer();
        } catch (ClassNotFoundException e) {
            log.error("Unable to load serializer", e);
            throw new LifecycleException(e);
        } catch (InstantiationException e) {
            log.error("Unable to load serializer", e);
            throw new LifecycleException(e);
        } catch (IllegalAccessException e) {
            log.error("Unable to load serializer", e);
            throw new LifecycleException(e);
        }
        log.info("Will expire sessions after " + getMaxInactiveInterval() + " seconds");
        initSessionsCollection();
    }

    private void initSessionsCollection() throws LifecycleException {
        ClientConfig clientConfig = null;
        try {
            clientConfig = new XmlClientConfigBuilder("hazelcast-client.xml").build();
        } catch (IOException e) {
            e.printStackTrace();
            throw new LifecycleException("Error loading Hazelcast configuration", e);
        }
        hazelcastClient = HazelcastClient.newHazelcastClient(clientConfig);
        sessions = hazelcastClient.getMap("sessions");
    }

    public void stop() throws LifecycleException {
        hazelcastClient.shutdown();
    }

    public Session findSession(String id) throws IOException {
        return loadSession(id);
    }

    public String[] keys() throws IOException {

        return (String[]) sessions.keySet().toArray();
    }


    public Session loadSession(String id) throws IOException {
        if (id == null || id.length() == 0) {
            return createEmptySession();
        }

        if (sessions.containsKey(id)) {
            StandardSession session = (StandardSession) sessions.get(id);
            currentSession.set(session);
            return session;
        } else {
            return createEmptySession();
        }
    }

    public void save(Session session) throws IOException {
        log.debug("Saving session " + session + " into Hazelcast");

        StandardSession standardsession = (HzSession) session;

        log.debug("Session Contents [" + session.getId() + "]:");
        for (Object name : Collections.list(standardsession.getAttributeNames())) {
            log.debug("  " + name);
        }

        sessions.put(standardsession.getId(), standardsession);
        currentSession.remove();
        log.debug("Session removed from ThreadLocal :" + session.getIdInternal());

    }

    public void remove(Session session) {
        sessions.remove(session.getId());
    }

    @Override
    public void removePropertyChangeListener(PropertyChangeListener propertyChangeListener) {
        //To change body of implemented methods use File | Settings | File Templates.
    }

    public void processExpires() {
        BasicDBObject query = new BasicDBObject();

        long olderThan = System.currentTimeMillis() - (getMaxInactiveInterval() * 1000);

        for (Iterator<Map.Entry<String, Object>> it = sessions.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<String, Object> entry = it.next();
            StandardSession session = (StandardSession) entry.getValue();
            if (session.)
            if (entry.getKey().equals("test")) {
                it.remove();
            }
        }

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


