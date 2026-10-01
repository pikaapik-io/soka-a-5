package project.setup;

import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.cloudbus.cloudsim.allocationpolicies.VmAllocationPolicy;
import org.cloudbus.cloudsim.datacenters.Datacenter;
import org.cloudbus.cloudsim.hosts.Host;
import org.cloudbus.cloudsim.vms.Vm;

/**
 * Host selection for VmAllocationPolicySimple that enforces the hard constraints
 * of the design (section 4.3). VmSchedulerTimeShared alone only checks MIPS and
 * would let a host run more VM PEs than it physically has.
 *
 * C3 sum of VM PEs <= host PEs, C4 RAM, C5 BW, C6 VM MIPS per PE <= host MIPS per PE.
 * Among suitable hosts, the one with the fewest PEs in use is chosen, which is
 * the VmAllocationPolicySimple rule.
 */
public final class ConstrainedPlacement {
	private ConstrainedPlacement() { }

	public static Optional<Host> findHost(VmAllocationPolicy policy, Vm vm) {
		return policy.getHostList().stream()
				.filter(host -> fits(host, vm))
				.min(Comparator.comparingLong(ConstrainedPlacement::usedPes));
	}

	static boolean fits(Host host, Vm vm) {
		return usedPes(host) + vm.getNumberOfPes() <= host.getNumberOfPes()   // C3
				&& vm.getMips() <= host.getMips()                             // C6
				&& host.isSuitableForVm(vm);                                  // C4, C5
	}

	static long usedPes(Host host) {
		return host.getVmList().stream().mapToLong(Vm::getNumberOfPes).sum();
	}

	/**
	 * Plans which datacenter receives each VM using the same rule as findHost
	 * (datacenters in order, then the host with the fewest PEs in use).
	 *
	 * CloudSim Plus 6.6.1 retries a VM that fails in DC-1 on DC-2, but never
	 * dispatches the cloudlets bound to such a VM. Sending every VM straight to
	 * its planned datacenter avoids the retry.
	 */
	public static Map<Vm, Datacenter> planDatacenters(List<Datacenter> datacenters, List<Vm> vms) {
		// Identity map: host IDs repeat across datacenters, so equals() would merge them.
		Map<Host, long[]> used = new IdentityHashMap<>(); // {PEs, RAM, BW}
		Map<Vm, Datacenter> plan = new IdentityHashMap<>();
		for (Vm vm : vms) {
			Host chosen = null;
			for (Datacenter dc : datacenters) {
				chosen = dc.getHostList().stream()
						.filter(host -> fitsPlanned(host, vm, used.computeIfAbsent(host, h -> new long[3])))
						.min(Comparator.comparingLong(host -> used.get(host)[0]))
						.orElse(null);
				if (chosen != null) {
					plan.put(vm, dc);
					break;
				}
			}
			if (chosen == null) {
				throw new IllegalStateException("No host can fit VM " + vm.getId());
			}
			long[] u = used.get(chosen);
			u[0] += vm.getNumberOfPes();
			u[1] += vm.getRam().getCapacity();
			u[2] += vm.getBw().getCapacity();
		}
		return plan;
	}

	private static boolean fitsPlanned(Host host, Vm vm, long[] used) {
		return used[0] + vm.getNumberOfPes() <= host.getNumberOfPes()                 // C3
				&& used[1] + vm.getRam().getCapacity() <= host.getRam().getCapacity()   // C4
				&& used[2] + vm.getBw().getCapacity() <= host.getBw().getCapacity()     // C5
				&& vm.getMips() <= host.getMips();                                      // C6
	}
}
