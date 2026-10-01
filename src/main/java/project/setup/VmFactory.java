package project.setup;

import java.util.ArrayList;
import java.util.List;
import org.cloudbus.cloudsim.schedulers.cloudlet.CloudletSchedulerTimeShared;
import org.cloudbus.cloudsim.vms.Vm;
import org.cloudbus.cloudsim.vms.VmSimple;

/**
 * 20 VMs from the project design (section 2.4). Index order is fixed:
 * VM 0-3 = V3 Large, VM 4-11 = V2 Medium, VM 12-19 = V1 Small.
 */
public final class VmFactory {

	/** VM type: count, PE count, MIPS per PE, RAM (MB), BW (Mbps). */
	public enum VmType {
		V3_LARGE(4, 4, 2_500, 8_192, 1_000),
		V2_MEDIUM(8, 2, 1_500, 4_096, 1_000),
		V1_SMALL(8, 1, 1_000, 2_048, 500);

		public final int count;
		public final int pes;
		public final double mipsPerPe;
		public final long ram;
		public final long bw;

		VmType(int count, int pes, double mipsPerPe, long ram, long bw) {
			this.count = count;
			this.pes = pes;
			this.mipsPerPe = mipsPerPe;
			this.ram = ram;
			this.bw = bw;
		}

		public String label() { return name().substring(0, 2); }
	}

	/** VM image size (MB). Not specified in the design. */
	private static final long VM_SIZE = 10_000;

	private VmFactory() { }

	public static List<Vm> createDefaultVms() {
		List<Vm> vms = new ArrayList<>();
		for (VmType type : VmType.values()) {
			for (int i = 0; i < type.count; i++) {
				Vm vm = new VmSimple(vms.size(), type.mipsPerPe, type.pes);
				vm.setRam(type.ram).setBw(type.bw).setSize(VM_SIZE)
						.setCloudletScheduler(new CloudletSchedulerTimeShared())
						.setDescription(type.label());
				vms.add(vm);
			}
		}
		return vms;
	}
}
