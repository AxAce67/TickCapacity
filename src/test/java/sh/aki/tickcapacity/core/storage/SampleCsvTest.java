package sh.aki.tickcapacity.core.storage;

import org.junit.jupiter.api.Test;
import sh.aki.tickcapacity.core.model.Sample;
import sh.aki.tickcapacity.core.model.TickSummary;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SampleCsvTest {

    @Test
    void roundTrip() {
        Sample s = new Sample(1_700_000_000_000L, 10_000, 7,
                new TickSummary(200, 12.5, 11.0, 20.25, 31.0, 48.0, 2500),
                19.9, 0.25, Double.NaN, 2400.5, 3000, 8192, 4, 37, 1500, 900, 120, 42);
        Sample back = SampleCsv.decode(SampleCsv.encode(s)).orElseThrow();
        assertEquals(s.players(), back.players());
        assertEquals(s.ticks(), back.ticks());
        assertTrue(Double.isNaN(back.systemCpu()));
        assertEquals(s.mainThreadCpuMs(), back.mainThreadCpuMs(), 1e-4);
        assertEquals(s.tickingBlockEntities(), back.tickingBlockEntities());
        assertEquals(42, back.newChunks());
    }

    @Test
    void readsVersion1RowsWithoutNewChunks() {
        String v1 = "1700000000000,10000,3,200,5.0,4.0,9.0,12.0,20.0,1000.0,20.0,0.1,0.2,900.0,1000,4000,1,5,100,50,10";
        Sample s = SampleCsv.decode(v1).orElseThrow();
        assertEquals(3, s.players());
        assertEquals(-1, s.newChunks());
        assertTrue(Double.isNaN(s.newChunksPerSecond()));
    }

    @Test
    void skipsHeaderAndGarbage() {
        assertTrue(SampleCsv.decode(SampleCsv.HEADER).isEmpty());
        assertTrue(SampleCsv.decode("").isEmpty());
        assertTrue(SampleCsv.decode("1,2,3").isEmpty());
        assertTrue(SampleCsv.decode("a,b,c,d,e,f,g,h,i,j,k,l,m,n,o,p,q,r,s,t,u").isEmpty());
    }
}
