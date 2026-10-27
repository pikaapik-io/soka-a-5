package project.setup;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Map;
import org.cloudbus.cloudsim.core.CloudSim;
import org.cloudbus.cloudsim.datacenters.Datacenter;
import org.cloudbus.cloudsim.hosts.Host;
import org.cloudbus.cloudsim.vms.Vm;
import org.junit.jupiter.api.Test;

class TopologyTest {
	private static final int[][] EXPECTED_PLACEMENT = {
			{0, 0}, {0, 1}, {0, 2}, {0, 0},
			{0, 1}, {0, 2}, {0, 1}, {0, 2},
			{1, 0}, {1, 1}, {1, 2}, {1, 0},
			{1, 1}, {1, 2}, {1, 1}, {1, 2},
			{1, 0}, {1, 1}, {1, 2}, {1, 0},
	};

	@Test
	void grandDesignHasTwoDatacentersSixHostsAndTwentyVms() {
		List<Datacenter> datacenters = DatacenterFactory.createAll(new CloudSim());
		List<Vm> vms = VmFactory.createDefaultVms();

		assertEquals(2, datacenters.size());
		assertEquals(3, datacenters.get(0).getHostList().size());
		assertEquals(3, datacenters.get(1).getHostList().size());
		assertEquals(6, datacenters.stream().mapToInt(dc -> dc.getHostList().size()).sum());
		assertEquals(20, vms.size());
		assertEquals(4, VmFactory.VmType.V3_LARGE.count);
		assertEquals(8, VmFactory.VmType.V2_MEDIUM.count);
		assertEquals(8, VmFactory.VmType.V1_SMALL.count);
	}

	@Test
	void deterministicPlacementMatchesRealWorldVmHostMap() {
		List<Datacenter> datacenters = DatacenterFactory.createAll(new CloudSim());
		List<Vm> vms = VmFactory.createDefaultVms();
		Map<Vm, Host> placement = ConstrainedPlacement.planHosts(datacenters, vms);

		for (int vmIndex = 0; vmIndex < vms.size(); vmIndex++) {
			Host host = placement.get(vms.get(vmIndex));
			int datacenterIndex = datacenters.indexOf(host.getDatacenter());
			int hostIndex = datacenters.get(datacenterIndex).getHostList().indexOf(host);
			assertEquals(EXPECTED_PLACEMENT[vmIndex][0], datacenterIndex, "VM " + vmIndex + " datacenter");
			assertEquals(EXPECTED_PLACEMENT[vmIndex][1], hostIndex, "VM " + vmIndex + " host");
		}
	}
}
