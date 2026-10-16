package app.localcache;

import org.junit.Test;

public class RuntimePolicyTest {
    @Test public void serializedResourceOwnership() throws Exception { SerialLifecycleChecks.runAll(); }
    @Test public void largeHttpResponseValidation() throws Exception { HttpTransferChecks.runAll(); }
}
