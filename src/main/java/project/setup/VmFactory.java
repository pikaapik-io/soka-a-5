package project.setup;

import java.util.ArrayList;
import java.util.List;
import org.cloudbus.cloudsim.vms.Vm;
import org.cloudbus.cloudsim.vms.VmSimple;

public final class VmFactory {
	private VmFactory() { }

	public static List<Vm> createDefaultVms() {
		List<Vm> vms = new ArrayList<>();
		add(vms, 4, 10_000, 2);
		add(vms, 8, 3_000, 2);
		add(vms, 8, 1_000, 1);
		return vms;
	}

	private static void add(List<Vm> vms, int count, double mips, int pes) {
		for (int i = 0; i < count; i++) vms.add(new VmSimple(mips, pes));
	}
}
