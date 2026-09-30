package project.setup;

import java.util.List;
import java.util.stream.Collectors;
import org.cloudbus.cloudsim.cloudlets.Cloudlet;
import org.cloudbus.cloudsim.cloudlets.CloudletSimple;
import org.cloudbus.cloudsim.datacenters.Datacenter;
import org.cloudbus.cloudsim.core.CloudSim;
import org.cloudbus.cloudsim.vms.Vm;

public final class SimulationSetup {
	private final CloudSim simulation;
	private final Datacenter datacenter;
	private final List<Vm> vmList;

	public SimulationSetup(double[] taskLengths) {
		simulation = new CloudSim();
		datacenter = DatacenterFactory.create(simulation);
		vmList = VmFactory.createDefaultVms();
		cloudlets = createCloudlets(taskLengths);
	}

	private final List<Cloudlet> cloudlets;

	private static List<Cloudlet> createCloudlets(double[] lengths) {
		return java.util.Arrays.stream(lengths)
				.mapToObj(length -> (Cloudlet) new CloudletSimple((long) length, 1))
			.collect(Collectors.toList());
	}

	public CloudSim simulation() { return simulation; }
	public Datacenter datacenter() { return datacenter; }
	public List<Vm> vmList() { return vmList; }
	public List<Cloudlet> cloudlets() { return cloudlets; }
}
