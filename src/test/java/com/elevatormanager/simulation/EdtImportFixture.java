package com.elevatormanager.simulation;

import com.elevatormanager.io.RequestFileReader;
import com.elevatormanager.model.SimulationConfig;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.swing.SwingUtilities;

/** T52: retiene la apertura y después usa el lector real; no fabrica su resultado. */
public final class EdtImportFixture {
    private EdtImportFixture() { }
    public static SimulationController create(SimulationConfig config, CountDownLatch entered,
            CountDownLatch release, AtomicBoolean ranOnEdt) {
        return SimulationController.withBatchLoader(config, 0, (path, current) -> {
            ranOnEdt.set(SwingUtilities.isEventDispatchThread()); entered.countDown();
            if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("No se liberó la lectura de prueba.");
            return new RequestFileReader().read(path, current);
        });
    }
}
