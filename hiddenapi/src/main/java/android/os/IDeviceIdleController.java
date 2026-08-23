package android.os;

/** Compile-only hidden framework stub matching RikkaW/HiddenApi. */
public interface IDeviceIdleController extends IInterface {
    void addPowerSaveTempWhitelistApp(String name, long duration, int userId, String reason);
    void addPowerSaveTempWhitelistApp(String name, long duration, int userId, int reasonCode,
                                      String reason);

    abstract class Stub extends Binder implements IDeviceIdleController {
        public static IDeviceIdleController asInterface(IBinder binder) {
            throw new RuntimeException("STUB");
        }
    }
}
