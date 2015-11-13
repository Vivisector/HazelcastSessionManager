package com.zooplus.runnable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Created by Igor Ivaniuk on 13.11.2015.
 */
public class LogTester {

    public static Logger logger = LoggerFactory.getLogger(LogTester.class);

    public static void main(String[] args) {

        logger.info("** Test");

    }
}
