package project.setup;

import java.util.ArrayList;
import java.util.List;
import org.cloudbus.cloudsim.datacenters.Datacenter;
import org.cloudbus.cloudsim.datacenters.DatacenterSimple;
import org.cloudbus.cloudsim.hosts.Host;
import org.cloudbus.cloudsim.hosts.HostSimple;
import org.cloudbus.cloudsim.resources.Pe;
import org.cloudbus.cloudsim.resources.PeSimple;
import org.cloudbus.cloudsim.schedulers.vm.VmSchedulerTimeShared;
import org.cloudbus.cloudsim.core.CloudSim;

public final class DatacenterFactory {
	private DatacenterFactory() { }

	public static Datacenter create(CloudSim simulation) {
		List<Host> hosts = new ArrayList<>();
		for (int i = 0; i < 6; i++) {
			List<Pe> pes = new ArrayList<>();
			for (int pe = 0; pe < 4; pe++) pes.add(new PeSimple(10_000));
			hosts.add(new HostSimple(32_768, 100_000, 1_000_000, pes)
					.setVmScheduler(new VmSchedulerTimeShared()));
		}
		return new DatacenterSimple(simulation, hosts);
	}
}
