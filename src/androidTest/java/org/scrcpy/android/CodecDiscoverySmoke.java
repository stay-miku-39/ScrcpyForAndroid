package org.scrcpy.android;

import android.content.*;
import android.widget.*;
import java.util.*;
import java.util.concurrent.*;
import static org.scrcpy.android.UiControlsSmoke.*;

final class CodecDiscoverySmoke {
    static String run(SmokeInstrumentation test, Context context) throws Exception {
        String prefix="codec-test-"+System.nanoTime();
        SharedPreferences fresh=context.getSharedPreferences(prefix+"-stream",0), state=context.getSharedPreferences(prefix+"-state",0);
        SharedPreferences upgrade=context.getSharedPreferences(prefix+"-upgrade",0), upgradeState=context.getSharedPreferences(prefix+"-upgrade-state",0);
        Map<String,?> realBefore=StreamPreferences.get(context).getAll(); MainActivity main=null;
        try {
            CountDownLatch ready=new CountDownLatch(1); CodecCatalog.start(context,ready::countDown);
            check(ready.await(15,TimeUnit.SECONDS),"Codec discovery timed out"); CodecCatalog catalog=CodecCatalog.cached;
            check(catalog != null && catalog.successful && !catalog.components.isEmpty(),"No local codec catalog");
            check(realBefore.equals(StreamPreferences.get(context).getAll()),"Upgrade discovery changed user configuration");
            check(CodecCatalog.initialize(fresh,state,catalog,true),"Fresh installation was not initialized");
            Map<String,String> recommended=CodecPolicy.recommend(catalog.components);
            for (Map.Entry<String,String> e:recommended.entrySet()) check(e.getValue().equals(fresh.getString(e.getKey(),"")),"Wrong initial codec combination: "+e.getKey());
            fresh.edit().putString("videoCodec","vp9").apply();
            check(!CodecCatalog.initialize(fresh,state,catalog,true) && "vp9".equals(fresh.getString("videoCodec","")),"Repeated startup replaced saved codec");
            check(!CodecCatalog.initialize(upgrade,upgradeState,catalog,false) && upgrade.getAll().isEmpty(),"Upgrade installed new defaults");
            upgradeState.edit().clear().commit(); upgrade.edit().putString("videoCodec","vp9").commit();
            check(!CodecCatalog.initialize(upgrade,upgradeState,catalog,true) && "vp9".equals(upgrade.getString("videoCodec","")),"Existing manual settings overwritten");
            main=(MainActivity)test.startActivitySync(new Intent(context,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); MainActivity activity=main;
            test.runOnMainSync(() -> {
                activity.ui.select("settings"); android.view.View root=activity.getWindow().getDecorView();
                Spinner video=root.findViewWithTag("stream_videoCodec");
                for(int i=0;i<StreamSettings.VIDEOS.length;i++) check(video.getAdapter().getItem(i).toString().contains("本机硬解") == catalog.hardwareDecode(StreamSettings.VIDEOS[i]),"Hardware label does not match catalog");
                video.setSelection(1); activity.ui.codecCatalogReady();
                check(video.getSelectedItemPosition()==1,"Capability refresh destroyed unsaved draft");
                check(((TextView)root.findViewWithTag("codecSupportSummary")).getText().toString().contains("编码运行在被控端"),"Local/remote capability scope missing");
            });
            return "PASS local codec discovery, first-install isolated defaults, upgrade preservation, hardware labels and draft retention";
        } finally {
            if(main!=null){MainActivity activity=main;test.runOnMainSync(activity::finish);}
            for(String suffix:new String[]{"-stream","-state","-upgrade","-upgrade-state"}) context.deleteSharedPreferences(prefix+suffix);
        }
    }
}
