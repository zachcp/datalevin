package datalevin.ni;

import org.graalvm.word.WordFactory;
import org.graalvm.nativeimage.UnmanagedMemory;
import org.graalvm.nativeimage.c.CContext;
import org.graalvm.nativeimage.c.type.CTypeConversion;
import org.graalvm.nativeimage.c.type.CIntPointer;
import org.graalvm.nativeimage.c.struct.SizeOf;

/**
 * Wrap MDB_dbi
 */
@CContext(Lib.Directives.class)
public class Dbi {

    private CIntPointer ptr;
    private String name;

    public Dbi(Env env, String name, int flags) {
        this.name = name;
        this.ptr = UnmanagedMemory.calloc(SizeOf.get(CIntPointer.class));
        Txn txn = Txn.create(env);
        Lib.checkRc(Lib.mdb_dbi_open(txn.get(),
                                     CTypeConversion.toCString(name).get(),
                                     flags,
                                     ptr));
        Lib.checkRc(Lib.dtlv_set_comparator(txn.get(), get()));

        txn.commit();
    }

    /**
     * Factory method to create an instance
     */
    public static Dbi create(Env env, String name, int flags) {
        return new Dbi(env, name, flags);
    }

    /**
     * Free memory
     */
    public void close() {
        UnmanagedMemory.free(ptr);
    }

    /**
     * Return the MDB_dbi integer to be used in LMDB calls
     */
    public int get() {
        return ptr.read();
    }

    public String getName() {
        return name;
    }

}
