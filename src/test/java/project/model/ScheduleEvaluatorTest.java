package project.model;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class ScheduleEvaluatorTest {
	private static final double[] TASKS = {900_000, 600_000, 450_000, 300_000, 150_000};
	private static final double[] VMS = {10_000, 10_000, 10_000, 10_000};
	private static final int[] MAPPING = {0, 1, 2, 3, 3};

	// One host with P_idle 100 W, P_max 200 W and 40.000 MIPS holds all four VMs.
	private static final ScheduleEvaluator EVALUATOR = new ScheduleEvaluator(new ScheduleContext(
			TASKS, VMS, new int[] {0, 0, 0, 0}, new double[] {100}, new double[] {200}, new double[] {40_000}));

	@Test
	void makespanOfDesignExampleIs90() {
		assertEquals(90.0, EVALUATOR.makespan(MAPPING), 1e-9);
		assertArrayEquals(new double[] {90, 60, 45, 45}, EVALUATOR.vmLoads(MAPPING), 1e-9);
	}

	@Test
	void energyMatchesHandCalculation() {
		ScheduleEvaluator.EnergyBreakdown energy = EVALUATOR.energyBreakdown(MAPPING);
		// idle: 100 W x 90 s; dynamic: (200 - 100) W x (10.000 / 40.000) x (90 + 60 + 45 + 45) s
		assertEquals(9_000, energy.idleJoules(), 1e-9);
		assertEquals(6_000, energy.dynamicJoules(), 1e-9);
		assertEquals(15_000 / 3_600_000.0, EVALUATOR.energyKwh(MAPPING), 1e-15);
	}

	@Test
	void invalidMappingIsRejected() {
		assertThrows(IllegalArgumentException.class, () -> EVALUATOR.makespan(new int[] {0, 1, 2, 3}));
		assertThrows(IllegalArgumentException.class, () -> EVALUATOR.makespan(new int[] {0, 1, 2, 3, 4}));
	}
}
