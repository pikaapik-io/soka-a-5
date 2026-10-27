package project.setup;

import java.util.ArrayList;
import java.util.List;
import org.cloudbus.cloudsim.schedulers.cloudlet.CloudletSchedulerSpaceShared;
import org.cloudbus.cloudsim.vms.Vm;
import org.cloudbus.cloudsim.vms.VmSimple;


// Jumlah VM dan rinciannya (jumlah, PE, MIPS, RAM per tipe) didefinisikan.
public final class VmFactory {

	/** Tipe VM: jumlah VM, jumlah PE, MIPS per PE, RAM (MB), BW (Mbps). */
	public enum VmType {
		// V3 Large, 4 VM (indeks 0-3), 4 PE x 2.500 MIPS, 8 GB RAM.
		// Hanya muat di DC-1 karena MIPS/PE V3 melebihi kemampuan host Tipe B.
		V3_LARGE(4, 4, 2_500, 8_192, 1_000),
		// V2 Medium, 8 VM (indeks 4-11), 2 PE x 1.500 MIPS, 4 GB RAM.
		V2_MEDIUM(8, 2, 1_500, 4_096, 1_000),
		// V1 Small, 8 VM (indeks 12-19), 1 PE x 1.000 MIPS, 2 GB RAM.
		V1_SMALL(8, 1, 1_000, 2_048, 500);

		/** Jumlah VM bertipe ini. */
		public final int count;
		/** Jumlah PE (vCPU) per VM. */
		public final int pes;
		/** MIPS setiap PE VM. */
		public final double mipsPerPe;
		/** RAM per VM dalam MB. */
		public final long ram;
		/** Bandwidth per VM dalam Mbps. */
		public final long bw;

		/** Constructor enum; urutan argumen sama dengan deklarasi konstanta di atas. */
		VmType(int count, int pes, double mipsPerPe, long ram, long bw) {
			this.count = count;
			this.pes = pes;
			this.mipsPerPe = mipsPerPe;
			this.ram = ram;
			this.bw = bw;
		}

		/**
		 * Label pendek tipe VM: dua huruf pertama nama enum ("V3", "V2", "V1").
		 * Disimpan sebagai description VM dan muncul di log serta CSV (kolom vm_type).
		 */
		public String label() { return name().substring(0, 2); }
	}

	/** Ukuran image VM (MB). Tidak ditentukan di desain (hanya perlu muat di storage host). */
	private static final long VM_SIZE = 10_000;

	// Kelas utilitas (hanya method static), constructor private.
	private VmFactory() { }

	// Total 20 VM = 4 V3 + 8 V2 + 8 V1, dibuat berurutan sesuai VmType.values().
	
	public static List<Vm> createDefaultVms() {
		List<Vm> vms = new ArrayList<>();
		for (VmType type : VmType.values()) {
			for (int i = 0; i < type.count; i++) {
				// VmSimple(id, MIPS per PE, jumlah PE).
				Vm vm = new VmSimple(vms.size(), type.mipsPerPe, type.pes);
				vm.setRam(type.ram).setBw(type.bw).setSize(VM_SIZE)
						.setCloudletScheduler(new CloudletSchedulerSpaceShared())
						.setDescription(type.label());
				vms.add(vm);
			}
		}
		return vms;
	}
}
