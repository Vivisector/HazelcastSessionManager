package com.zooplus.session;

import org.apache.catalina.Manager;
import org.apache.catalina.session.StandardSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * Created by Igor Ivaniuk on 13.11.2015.
 */
public class HzSession extends StandardSession {

    private static Logger log = LoggerFactory.getLogger("HzSession");
    private boolean isValid = true;

    public HzSession(Manager manager) {
        super(manager);
    }

    @Override
    protected boolean isValidInternal() {
        return isValid;
    }

    @Override
    public boolean isValid() {
        return isValidInternal();
    }

    @Override
    public void setValid(boolean isValid) {
        this.isValid = isValid;
        if (!isValid) {
            String keys[] = keys();
            for (String key : keys) {
                removeAttributeInternal(key, false);
            }
            getManager().remove(this);

        }
    }

    @Override
    public void invalidate() {
        setValid(false);
    }

    @Override
    public void setId(String id) {
        this.id = id;
    }
}
