package org.nfcresearch.qpolllab;
import java.io.IOException;

public final class ConnectionRetryTest {
    private static void require(boolean ok){if(!ok)throw new AssertionError();}
    public static void main(String[] args)throws Exception {
        int[] calls={0,0};
        ConnectionRetry.run(()->{if(++calls[0]<3)throw new ConnectionRetry.BeforeApdu(new IOException());},()->calls[1]++);
        require(calls[0]==3&&calls[1]==2);
        calls[0]=calls[1]=0;
        try {ConnectionRetry.run(()->{calls[0]++;throw new ConnectionRetry.BeforeApdu(new IOException());},()->calls[1]++);throw new AssertionError();}
        catch(ConnectionRetry.BeforeApdu expected){require(calls[0]==3&&calls[1]==2);}
        // A failed APDU, configuration check, or cancellation must never replay the session.
        for(Exception failure:new Exception[]{new IOException("D3 failed"),new IllegalArgumentException("profile"),new InterruptedException("stop")}) {
            calls[0]=calls[1]=0;
            try {ConnectionRetry.run(()->{calls[0]++;throw failure;},()->calls[1]++);throw new AssertionError();}
            catch(Exception expected){require(expected==failure&&calls[0]==1&&calls[1]==0);}
        }
        calls[0]=calls[1]=0;
        try {ConnectionRetry.run(()->{calls[0]++;throw new ConnectionRetry.BeforeApdu(new IOException());},()->{calls[1]++;throw new InterruptedException("stop during rediscovery");});throw new AssertionError();}
        catch(InterruptedException expected){require(calls[0]==1&&calls[1]==1);}
        System.out.println("ConnectionRetry tests passed: fresh discovery, budget, no APDU replay, cancellation");
    }
}
