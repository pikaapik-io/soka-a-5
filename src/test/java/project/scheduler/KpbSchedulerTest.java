package project.scheduler;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import project.setup.VmFactory;

class KpbSchedulerTest {
	@Test
	void twentyVmTopologyHasExpectedKpbCandidatePools() {
		assertEquals(4, new KpbScheduler(20).subsetSize(VmFactory.VmType.V3_LARGE.count
				+ VmFactory.VmType.V2_MEDIUM.count + VmFactory.VmType.V1_SMALL.count));
		assertEquals(10, new KpbScheduler(50).subsetSize(20));
		assertEquals(20, new KpbScheduler(100).subsetSize(20));
	}

	@Test
	void kTwentySchedulesSampleTasksAcrossTheFourV3Vms() {
		double[] capacities = new double[20];
		for (int i = 0; i < 4; i++) {
			capacities[i] = 10_000;
		}
		for (int i = 4; i < 12; i++) {
			capacities[i] = 3_000;
		}
		for (int i = 12; i < 20; i++) {
			capacities[i] = 1_000;
		}

		int[] mapping = new KpbScheduler(20).schedule(
				new double[] {900_000, 600_000, 450_000, 300_000, 150_000}, capacities);

		assertArrayEquals(new int[] {0, 1, 2, 3, 3}, mapping);
	}
}
