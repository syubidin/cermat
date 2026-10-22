package id.cermat.support;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/** Jam palsu yang bisa dimajukan manual: menguji logika berbasis waktu tanpa menunggu. */
public class MutableClock extends Clock {

    private Instant now;

    public MutableClock(Instant start) { this.now = start; }

    public void advanceSeconds(long seconds) { now = now.plusSeconds(seconds); }

    @Override public ZoneId getZone() { return ZoneOffset.UTC; }

    @Override public Clock withZone(ZoneId zone) { return this; }

    @Override public Instant instant() { return now; }
}
