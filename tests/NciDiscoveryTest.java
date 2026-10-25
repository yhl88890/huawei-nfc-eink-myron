package org.nfcresearch.qpolllab;
public final class NciDiscoveryTest {
    private static void check(boolean value){if(!value)throw new AssertionError();}
    public static void main(String[] args){
        check(NciDiscovery.hasQPoll("210303017101"));
        check(NciDiscovery.hasQPoll("21030703000171010101"));
        check(!NciDiscovery.hasQPoll("210309040001010102010601"));
        check(!NciDiscovery.hasQPoll("210303010071"));
        check(!NciDiscovery.hasQPoll("2103030171"));
        check(!NciDiscovery.hasQPoll("210303027101"));
        check(!NciDiscovery.hasQPoll("bad"));
        System.out.println("NCI Q-Poll discovery tests passed");
    }
}
