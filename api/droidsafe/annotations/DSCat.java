package droidsafe.annotations;

public enum DSCat {
    //Safe Categories */
    ANDROID_ANIMATION,
    ANDROID_CALLBACK,   //methods like onClick 
    OS_GENERAL,
    FS_INFO,
    DATA_STRUCTURE,  
    DB_CURSOR,
    MEM_BUFFER,
    SAFE_LIST,
    GUI,    
    GRAPHICS,
    NO_ACTION,
    DATA_GENERAL,
    SAFE_OTHERS,
    UTIL_FUNCTION,
    SOURCE,

    //Ban Categories
    DALVIK,
    PRIVATE_METHOD,
    REFLECTION,
    CLASS_LOADER,
    SECURITY_VIOLATION,
    DROIDSAFE_INTERNAL,
    BAN_OTHERS,
    
    //Spec Categories
    ABSTRACT_METHOD,
    ANDROID_ACCOUNT,
    ANDROID_ACTIVITY_STARTING,
    ANDROID_APPLICATION,
    ANDROID_INTERNAL,
    ANDROID_INSTRUMENTATION,
    ANDROID_LOW_LEVEL,
    ANDROID_MANAGER,
    ANDROID_NOTIFICATION_STARTING,
    ANDROID_LOADER,
    AUDIO_CALL,
    AUDIO_RECORDING,
    APP_RESOURCE,
    CALLBACK_INVOKE,
    CALLBACK_REG,
    
    BACKUP_SUBSYSTEM,
    BLUETOOTH,
    NFC,
    CONTACT,
    CONTENT,
    TO_MODEL, //once the method is modeled, it will be safe

    DATABASE,  //database changing
    DEFAULT_MODIFIER,  
    DEVICE,    
    
    EXEC,
    
    FILE_SYSTEM,
    MONITORING,

    GPS,
    INTERNET,
    INTENT_EXCHANGE,
    INTERGUI_ACTION,
    IO,
    IO_ACTION_METHOD,
    IPC,
    SERVICE,
    JAVA_SECURITY,
    LOGGING,
    LOCATION,    
    MEDIA_RECORDER,
    NETWORK_STATS,
    NETWORK,
    NETWORKING,
    
    OS_LOW_LEVEL,    
    PACKAGE_INFO,
    PACKAGE_MANAGER,
    PHONE_CALL,
    PHONE_STATE,
    POWER_MANAGER,
    RESOURCE,
    RTP_CALL,
    SENSOR,
    SIP_REGISTRATION,
    SMS,
    SHARING,
    STORAGE,
    STORAGE_STATE,
    SYSTEM_SETTING,    
    SYSTEM_SERVICE,
    STORAGE_ACCESS,
    SYSTEM,
    SYSTEM_PREFERENCES,
    SHARED_PREFERENCES,
    SYSTEM_SETTINGS,
    SECURITY,
    SERIALIZATION,
    TELEPHONY,
    XML,
    
    THREADING, //threading/execution
    TRIGGER,
    URI_EXCHANGE,
    WIFI,
    
    SPEC_OTHERS,
    TIME,

    IPC_CALLBACK,

    //Default value, not SAFE, BAN nor SPEC
    UNSPECIFIED;
}
