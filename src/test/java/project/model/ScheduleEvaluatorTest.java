package project.model;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/**
 * Unit test (JUnit 5) untuk {@link ScheduleEvaluator}: memastikan rumus makespan
 * (desain f1) dan rumus energi analitik menghasilkan angka yang sama dengan
 * hitungan tangan, serta mapping yang tidak valid ditolak.
 *
 * <p>Data uji memakai contoh desain: 5 task dan 4 VM identik 10.000 MIPS
 * (setara 4 VM V3), dengan mapping [0, 1, 2, 3, 3] -- mapping yang sama dengan
 * hasil KPB k = 20% pada data contoh bawaan Experiment.</p>
 */
class ScheduleEvaluatorTest {
	// Panjang task (MI) dari contoh desain.
	private static final double[] TASKS = {900_000, 600_000, 450_000, 300_000, 150_000};
	// Empat VM identik, masing-masing 10.000 MIPS.
	private static final double[] VMS = {10_000, 10_000, 10_000, 10_000};
	// Task 0->VM0, 1->VM1, 2->VM2, task 3 dan 4 -> VM3.
	private static final int[] MAPPING = {0, 1, 2, 3, 3};

	// Satu host dengan P_idle 100 W, P_max 200 W dan 40.000 MIPS menampung keempat VM.
	// (Angka daya ini sengaja dibuat bulat agar mudah dihitung manual, bukan angka host desain.)
	private static final ScheduleEvaluator EVALUATOR = new ScheduleEvaluator(new ScheduleContext(
			TASKS, VMS, new int[] {0, 0, 0, 0}, new double[] {100}, new double[] {200}, new double[] {40_000}));

	/**
	 * Load tiap VM = jumlah L / C: VM0 = 900.000/10.000 = 90 s, VM1 = 60 s,
	 * VM2 = 45 s, VM3 = (300.000 + 150.000)/10.000 = 45 s. Makespan = max = 90 s.
	 */
	@Test
	void makespanOfDesignExampleIs90() {
		assertEquals(90.0, EVALUATOR.makespan(MAPPING), 1e-9);
		assertArrayEquals(new double[] {90, 60, 45, 45}, EVALUATOR.vmLoads(MAPPING), 1e-9);
	}

	/**
	 * Energi idle = 100 W x 90 s = 9.000 J; energi dinamis =
	 * (200 - 100) W x (10.000 / 40.000) x (90 + 60 + 45 + 45) s = 100 x 0,25 x 240 = 6.000 J.
	 * Total 15.000 J = 15.000 / 3.600.000 kWh.
	 */
	@Test
	void energyMatchesHandCalculation() {
		ScheduleEvaluator.EnergyBreakdown energy = EVALUATOR.energyBreakdown(MAPPING);
		// idle: 100 W x 90 s; dynamic: (200 - 100) W x (10.000 / 40.000) x (90 + 60 + 45 + 45) s
		assertEquals(9_000, energy.idleJoules(), 1e-9);
		assertEquals(6_000, energy.dynamicJoules(), 1e-9);
		assertEquals(15_000 / 3_600_000.0, EVALUATOR.energyKwh(MAPPING), 1e-15);
	}

	/**
	 * Mapping harus berisi tepat satu VM per task dengan indeks VM yang valid:
	 * mapping dengan 4 elemen (kurang dari 5 task) dan mapping yang memuat VM 4
	 * (hanya ada VM 0-3) harus memicu IllegalArgumentException.
	 */
	@Test
	void invalidMappingIsRejected() {
		assertThrows(IllegalArgumentException.class, () -> EVALUATOR.makespan(new int[] {0, 1, 2, 3}));
		assertThrows(IllegalArgumentException.class, () -> EVALUATOR.makespan(new int[] {0, 1, 2, 3, 4}));
	}
}
