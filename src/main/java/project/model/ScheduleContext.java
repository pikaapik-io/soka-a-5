package project.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.cloudbus.cloudsim.core.CloudSim;
import org.cloudbus.cloudsim.datacenters.Datacenter;
import org.cloudbus.cloudsim.hosts.Host;
import org.cloudbus.cloudsim.vms.Vm;
import project.setup.CloudSimAdapter;
import project.setup.ConstrainedPlacement;
import project.setup.DatacenterFactory;
import project.setup.VmFactory;

/**
 * Everything {@link ScheduleEvaluator} needs beyond taskLength/vmCapacity: which
 * host each VM lives on, and each host's idle/max power and total MIPS (design
 * section 3.2). Shared by every scheduler (KPB, HS, CSA) so the fitness function
 * is computed the same way everywhere.
 */
public final class ScheduleContext {
	private final double[] taskLength;
	private final double[] vmCapacity;
	private final int[] vmHost;
	private final double[] hostIdlePower;
	private final double[] hostMaxPower;
	private final double[] hostTotalMips;

	public ScheduleContext(double[] taskLength, double[] vmCapacity, int[] vmHost,
							double[] hostIdlePower, double[] hostMaxPower, double[] hostTotalMips) {
		if (taskLength == null || vmCapacity == null || vmHost == null
				|| hostIdlePower == null || hostMaxPower == null || hostTotalMips == null) {
			throw new IllegalArgumentException("All arguments are required");
		}
		if (vmCapacity.length == 0 || vmCapacity.length != vmHost.length) {
			throw new IllegalArgumentException("vmCapacity and vmHost must have the same, non-zero length");
		}
		if (hostIdlePower.length == 0 || hostIdlePower.length != hostMaxPower.length
				|| hostIdlePower.length != hostTotalMips.length) {
			throw new IllegalArgumentException("Host arrays must have the same, non-zero length");
		}
		for (int host : vmHost) {
			if (host < 0 || host >= hostIdlePower.length) {
				throw new IllegalArgumentException("vmHost contains an invalid host index");
			}
		}
		this.taskLength = taskLength.clone();
		this.vmCapacity = vmCapacity.clone();
		this.vmHost = vmHost.clone();
		this.hostIdlePower = hostIdlePower.clone();
		this.hostMaxPower = hostMaxPower.clone();
		this.hostTotalMips = hostTotalMips.clone();
	}

	/**
	 * Builds the context from the project design (VmFactory/DatacenterFactory),
	 * using {@link ConstrainedPlacement#planHosts} for VM-to-host placement --
	 * the same deterministic placement CloudSim's VmAllocationPolicySimple
	 * produces at runtime (verified against results/simulation/placement.csv).
	 */
	public static ScheduleContext fromDesign(double[] taskLength) {
		CloudSim simulation = new CloudSim();
		List<Datacenter> datacenters = DatacenterFactory.createAll(simulation);
		List<Host> hosts = new ArrayList<>();
		datacenters.forEach(dc -> hosts.addAll(dc.getHostList()));
		List<Vm> vms = VmFactory.createDefaultVms();
		Map<Vm, Host> placement = ConstrainedPlacement.planHosts(datacenters, vms);

		double[] vmCapacity = CloudSimAdapter.vmCapacities(vms);
		int[] vmHost = new int[vms.size()];
		for (int i = 0; i < vms.size(); i++) {
			vmHost[i] = hosts.indexOf(placement.get(vms.get(i)));
		}

		double[] idlePower = new double[hosts.size()];
		double[] maxPower = new double[hosts.size()];
		double[] totalMips = new double[hosts.size()];
		for (int h = 0; h < hosts.size(); h++) {
			DatacenterFactory.HostType type = DatacenterFactory.typeOf(hosts.get(h));
			idlePower[h] = type.idlePower;
			maxPower[h] = type.maxPower;
			totalMips[h] = type.totalMips();
		}
		return new ScheduleContext(taskLength, vmCapacity, vmHost, idlePower, maxPower, totalMips);
	}

	/** Same hosts and VM placement, different tasks/capacities (what Scheduler.schedule() receives). */
	public ScheduleContext withTasks(double[] newTaskLength, double[] newVmCapacity) {
		return new ScheduleContext(newTaskLength, newVmCapacity, vmHost, hostIdlePower, hostMaxPower, hostTotalMips);
	}

	public double[] taskLength() { return taskLength.clone(); }
	public double[] vmCapacity() { return vmCapacity.clone(); }
	public int[] vmHost() { return vmHost.clone(); }
	public double[] hostIdlePower() { return hostIdlePower.clone(); }
	public double[] hostMaxPower() { return hostMaxPower.clone(); }
	public double[] hostTotalMips() { return hostTotalMips.clone(); }
	public int taskCount() { return taskLength.length; }
	public int vmCount() { return vmCapacity.length; }
	public int hostCount() { return hostIdlePower.length; }
}
