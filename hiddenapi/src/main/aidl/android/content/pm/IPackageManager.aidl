package android.content.pm;

interface IPackageManager{
    String getNameForUid(int uid);
    String[] getPackagesForUid(int uid);
    ResolveInfo resolveIntent(in Intent intent, String resolvedType, long flags, int userId);
}