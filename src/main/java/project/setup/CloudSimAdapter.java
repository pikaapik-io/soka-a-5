package project.setup;

import java.util.List;
import org.cloudbus.cloudsim.cloudlets.Cloudlet;
import org.cloudbus.cloudsim.vms.Vm;

public final class CloudSimAdapter {
	private CloudSimAdapter() { }

	public static double[] taskLengths(List<? extends Cloudlet> cloudlets) {
		return cloudlets.stream().mapToDouble(Cloudlet::getTotalLength).toArray();
	}

	public static double[] vmCapacities(List<? extends Vm> vms) {
		return vms.stream().mapToDouble(vm -> vm.getMips() * vm.getNumberOfPes()).toArray();
	}
}
