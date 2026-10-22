package org.scrcpy.android;

import android.content.Context;
import android.graphics.Typeface;
import android.text.InputType;
import android.view.*;
import android.widget.*;
import java.util.*;

/** A saved draft affects only sessions created after saving. No active-session observer. */
final class StreamSettingsView extends LinearLayout {
    private final Map<String, EditText> fields = new LinkedHashMap<>();
    private final Map<String, Spinner> choices = new LinkedHashMap<>();
    private final Map<String, String[]> keys = new LinkedHashMap<>();
    private final Map<String, String[]> baseLabels = new LinkedHashMap<>();
    private Map<String,String> loaded;
    private TextView codecStatus;
    private TextView result;
    private Button save;
    private TextView ratePreview;
    StreamSettingsView(Context context, java.util.function.Consumer<Map<String, String>> saveAction) {
        super(context); setOrientation(VERTICAL); setTag("streamSettings");
        Map<String, String> saved = StreamPreferences.snapshot(context).values;
        loaded = new LinkedHashMap<>(saved);
        label("分辨率与音视频", true);
        label("整个设置页仅点击底部“保存全部设置”后写入配置；离开页面会丢弃未保存修改。分辨率与音视频参数仅新建投屏会话生效，关闭投屏再打开即可，无需断开 ADB。", false);
        select("resolution", "远端逻辑显示尺寸", StreamSettings.RESOLUTIONS, new String[]{"远程目标原有分辨率", "本地主控屏幕分辨率", "本机比例 · 短边 720", "本机比例 · 短边 1080", "本机比例 · 短边 1440", "720 × 1280", "1080 × 1920", "1440 × 2560", "自定义宽高"}, saved);
        field("customWidth", "自定义宽度（px，128–8192）", saved, true); field("customHeight", "自定义高度（px，128–8192）", saved, true);
        select("backend", "优先实现方式", new String[]{"main", "virtual"}, new String[]{"修改被控主屏尺寸，失败尝试虚拟显示", "独立虚拟显示，失败尝试修改主屏"}, saved);
        label("虚拟显示是独立桌面，可能与手机当前画面不同；原始尺寸也遵循所选路线。两种方式都失败时使用原始主屏。主屏尺寸覆盖在远控会话关闭时恢复。全屏和小窗均等比完整显示。", false);
        field("virtualApp", "虚拟显示启动应用包名（可留空）", saved, false);
        label("留空时尝试系统默认桌面，再尝试系统设置。虚拟屏中的任务结束后会尝试移回主屏。", false);
        field("maxSize", "视频传输最大边（px，0 不限；默认 1600）", saved, true);
        select("videoCodec", "视频编码格式", StreamSettings.VIDEOS, new String[]{"H.264（兼容优先）", "H.265 / HEVC", "AV1", "VP8", "VP9"}, saved);
        field("videoBitRateLimit", "视频码率上限（bit/s，20000000 = 20 Mbps）", saved, true);
        label("软上限：按约 1 秒输出窗口超限时降低编码目标，关键帧可短时超过；不丢视频帧、不延迟发包。编码器不支持动态调节时会提示。通常保持默认目标 8 Mbps，增加上限不会要求设备填满带宽。",false);
        ratePreview = label("",false); ratePreview.setTag("stream_ratePreview");
        field("fps", "最高帧率（1–240，默认 60）", saved, true);
        select("audioCodec", "音频编码格式", StreamSettings.AUDIOS, new String[]{"PCM（无压缩）", "AAC", "Opus", "FLAC（无损）"}, saved);
        field("audioBitRate", "音频码率（bit/s，16000–512000）", saved, true);
        label("PCM 不使用码率或编解码组件设置；FLAC 的实际码率取决于内容。声音开关仍在连接页。组件不可用会提示并回退 H.264 / PCM。", false);
        codecStatus = label("正在检测本机编解码器能力…",false); codecStatus.setTag("codecSupportSummary");
        LinearLayout advanced = new LinearLayout(context); advanced.setOrientation(VERTICAL); advanced.setVisibility(GONE);
        Button expand = new Button(context); expand.setText("高级：目标码率与编解码组件"); expand.setTag("stream_advanced"); addView(expand); expand.setOnClickListener(v -> advanced.setVisibility(advanced.getVisibility() == GONE ? VISIBLE : GONE));
        select(advanced,"videoTargetMode", "目标码率方式", new String[]{"default","auto","custom"},new String[]{"默认目标 · 8 Mbps（不超过上限）","自动比例 · 上限的 80%，最高 8 Mbps","自定义目标码率"},saved);
        fields.put("videoBitRate",makeField(advanced,"videoBitRate","自定义目标码率（bit/s，仅自定义方式生效）",saved.get("videoBitRate"),true));
        select(advanced,"videoBitRateMode", "编码器码率控制", new String[]{"auto","cbr"},new String[]{"自动（与桌面 scrcpy 默认一致）","恒定码率 CBR（需远端支持）"},saved);
        for (String[] item : new String[][]{{"videoEncoder", "被控视频编码器"}, {"videoDecoder", "本地视频解码器"}, {"audioEncoder", "被控音频编码器"}, {"audioDecoder", "本地音频解码器"}}) {
            EditText input = makeField(advanced, item[0], item[1] + "（组件名称，留空自动）", saved.get(item[0]), false); fields.put(item[0], input);
        }
        addView(advanced);
        android.text.TextWatcher rateWatcher = new android.text.TextWatcher() {
            public void beforeTextChanged(CharSequence s,int start,int count,int after) { }
            public void onTextChanged(CharSequence s,int start,int before,int count) { updateRatePreview(); }
            public void afterTextChanged(android.text.Editable text) { }
        };
        fields.get("videoBitRateLimit").addTextChangedListener(rateWatcher); fields.get("videoBitRate").addTextChangedListener(rateWatcher);
        choices.get("videoTargetMode").setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            public void onItemSelected(AdapterView<?> parent,View view,int position,long id) { updateRatePreview(); }
            public void onNothingSelected(AdapterView<?> parent) { }
        });
        updateRatePreview();
        result = label("", false); result.setTag("streamSettingsResult");
        save = new Button(context); save.setText("保存全部设置"); save.setTag("saveStreamSettings"); addView(save);
        save.setOnClickListener(v -> {
            try {
                Map<String, String> values = new LinkedHashMap<>();
                for (Map.Entry<String, EditText> e : fields.entrySet()) values.put(e.getKey(), e.getValue().getText().toString().trim());
                for (Map.Entry<String, Spinner> e : choices.entrySet()) values.put(e.getKey(), keys.get(e.getKey())[e.getValue().getSelectedItemPosition()]);
                StreamSettings.validate(values); saveAction.accept(values); loaded = new LinkedHashMap<>(values); result.setText("全部设置已保存。分辨率与音视频参数在关闭投屏后重新打开生效。");
            } catch (IllegalArgumentException error) { result.setText("未保存：" + error.getMessage()); }
        });
        refreshCodecLabels();
    }
    void moveSaveControls(LinearLayout parent) { removeView(result); removeView(save); parent.addView(result); parent.addView(save); }
    /** Navigation never treats the in-memory editor as a saved configuration. */
    void reloadSaved() {
        Map<String, String> saved = StreamPreferences.snapshot(getContext()).values;
        for (Map.Entry<String, EditText> entry : fields.entrySet()) entry.getValue().setText(saved.get(entry.getKey()));
        for (Map.Entry<String, Spinner> entry : choices.entrySet()) {
            entry.getValue().setSelection(Math.max(0, Arrays.asList(keys.get(entry.getKey())).indexOf(saved.get(entry.getKey()))));
        }
        result.setText(""); clearFocus();
        loaded = new LinkedHashMap<>(saved);
        updateRatePreview();
    }
    private void updateRatePreview() {
        boolean custom = choices.get("videoTargetMode").getSelectedItemPosition()==2;
        fields.get("videoBitRate").setEnabled(custom);
        try {
            Map<String,String> values = new LinkedHashMap<>(StreamSettings.DEFAULTS);
            values.put("videoBitRateLimit",fields.get("videoBitRateLimit").getText().toString().trim());
            values.put("videoBitRate",fields.get("videoBitRate").getText().toString().trim());
            values.put("videoTargetMode",keys.get("videoTargetMode")[choices.get("videoTargetMode").getSelectedItemPosition()]);
            StreamSettings.validate(values); int limit=Integer.parseInt(values.get("videoBitRateLimit")), target=StreamSettings.targetBitRate(values);
            ratePreview.setText(String.format(Locale.ROOT,"新会话：目标 %.2f Mbps（上限的 %.0f%%）· 软上限 %.2f Mbps",target/1e6,target*100.0/limit,limit/1e6));
        } catch (RuntimeException error) { ratePreview.setText("请填写有效码率；自定义目标不能超过上限。"); }
    }
    void refreshCodecLabels() {
        CodecCatalog catalog = CodecCatalog.cached; if (catalog == null) return;
        boolean clean = true;
        for (Map.Entry<String, EditText> e : fields.entrySet()) if (!e.getValue().getText().toString().trim().equals(loaded.get(e.getKey()))) clean=false;
        for (Map.Entry<String, Spinner> e : choices.entrySet()) if (!keys.get(e.getKey())[e.getValue().getSelectedItemPosition()].equals(loaded.get(e.getKey()))) clean=false;
        for (String kind : new String[]{"videoCodec","audioCodec"}) {
            Spinner choice = choices.get(kind); int selected=choice.getSelectedItemPosition(); String[] labels=baseLabels.get(kind).clone(), formats=keys.get(kind);
            for (int i=0;i<labels.length;i++) if (catalog.hardwareDecode(formats[i])) labels[i]+=" · 本机硬解";
            ArrayAdapter<String> adapter=new ArrayAdapter<>(getContext(),android.R.layout.simple_spinner_item,labels); adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
            choice.setAdapter(adapter); choice.setSelection(selected);
        }
        codecStatus.setText(catalog.successful ? catalog.summary() : "未能读取本机硬件能力，可手动选择；连接失败时按兼容格式回退。");
        if (clean) reloadSaved();
    }
    private TextView label(String text, boolean title) {
        TextView view = new TextView(getContext()); view.setText(text); view.setTextSize(title ? 18 : 14); view.setTextColor(title ? MainUi.INK : MainUi.MUTED);
        view.setPadding(0, MainUi.dp(getContext(), 8), 0, MainUi.dp(getContext(), 8)); if (title) view.setTypeface(null, Typeface.BOLD); addView(view); return view;
    }
    private void field(String key, String text, Map<String, String> saved, boolean numeric) { fields.put(key, makeField(this, key, text, saved.get(key), numeric)); }
    private EditText makeField(LinearLayout parent, String key, String text, String value, boolean numeric) {
        TextView label = new TextView(getContext()); label.setText(text); label.setTextColor(MainUi.MUTED); parent.addView(label);
        EditText field = new EditText(getContext()); field.setTag("stream_" + key); field.setSingleLine(true); field.setText(value); field.setContentDescription(text);
        field.setInputType(numeric ? InputType.TYPE_CLASS_NUMBER : InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS); parent.addView(field, new LayoutParams(-1, -2)); return field;
    }
    private void select(String key, String text, String[] values, String[] labels, Map<String, String> saved) {
        select(this,key,text,values,labels,saved);
    }
    private void select(LinearLayout parent,String key, String text, String[] values, String[] labels, Map<String, String> saved) {
        baseLabels.put(key, labels.clone());
        TextView title = new TextView(getContext()); title.setText(text); title.setTextColor(MainUi.MUTED); parent.addView(title);
        Spinner spinner = new Spinner(getContext()); spinner.setTag("stream_" + key); spinner.setContentDescription(text);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(getContext(), android.R.layout.simple_spinner_item, labels); adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter); spinner.setSelection(Math.max(0, Arrays.asList(values).indexOf(saved.get(key)))); parent.addView(spinner, new LayoutParams(-1, MainUi.dp(getContext(), 52))); choices.put(key, spinner); keys.put(key, values);
    }
}
