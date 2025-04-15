package com.jetbridge.reactobd2;

import android.util.Log;
import com.facebook.react.ReactPackage;
import com.facebook.react.bridge.NativeModule;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.uimanager.ViewManager;
import java.util.ArrayList;
import java.util.List;

public class ReactNativeOBD2Package implements ReactPackage {
  private static final String TAG = "ReactNativeOBD2Package";

  @Override
  public List<NativeModule> createNativeModules(ReactApplicationContext reactContext) {
    Log.d(TAG, "createNativeModules: Creating native modules.");
    List<NativeModule> modules = new ArrayList<>();
    modules.add(new ReactNativeOBD2Module(reactContext));
    Log.d(TAG, "createNativeModules: Added ReactNativeOBD2Module.");
    return modules;
  }

  // @Override
  // public List<Class<? extends JavaScriptModule>> createJSModules() {
  //   return Collections.emptyList();
  // }

  @Override
  public List<ViewManager> createViewManagers(ReactApplicationContext reactContext) {
    Log.d(TAG, "createViewManagers: Creating view managers (none by default).");
    List<ViewManager> result = new ArrayList<>();
    return result;
  }
}
