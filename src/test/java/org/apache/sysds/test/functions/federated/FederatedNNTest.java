package org.apache.sysds.test.functions.federated;

import org.apache.sysds.common.Types;
import org.junit.Test;
import org.apache.sysds.test.AutomatedTestBase;
import org.apache.sysds.test.TestConfiguration;
import org.apache.sysds.test.TestUtils;

public class FederatedNNTest extends AutomatedTestBase {
    private final static String TEST_DIR = "functions/federated/";
    private final static String TEST_NAME = "FederatedNNTest";
    private final static String TEST_CLASS_DIR = TEST_DIR + FederatedNNTest.class.getSimpleName() + "/";

    @Override
    public void setUp() {
        TestUtils.clearAssertionInformation();
        addTestConfiguration(TEST_NAME, new TestConfiguration(TEST_CLASS_DIR, TEST_NAME, new String[] {"Z"}));
    }

    @Test
    public void federatedNN(Types.ExecMode execMode) {

        int port1 = getRandomAvailablePort();
        int port2 = getRandomAvailablePort();
        int port3 = getRandomAvailablePort();
        int port4 = getRandomAvailablePort();
        Thread t1 = startLocalFedWorker(port1);
        Thread t2 = startLocalFedWorker(port2);
        Thread t3 = startLocalFedWorker(port3);
        Thread t4 = startLocalFedWorker(port4);
    }
}
