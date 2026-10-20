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
    StreamSettingsView(Context context) {
        super(context); setOrientation(VERTICAL); setTag("streamSettings");
        Map<String, String> saved = StreamPreferences.snapshot(context).values;
        label("分辨率与音视频", true);
        label("全局设置，仅保存后新建的投屏会话生效。已有全屏、小窗、后台会话及窗口交接保持原配置。关闭投屏再打开即可应用，无需断开 ADB。", false);
        select("resolution", "远端逻辑显示尺寸", StreamSettings.RESOLUTIONS, new String[]{"远程目标原有分辨率", "本地主控屏幕分辨率", "本机比例 · 短边 720", "本机比例 · 短边 1080", "本机比例 · 短边 1440", "720 × 1280", "1080 × 1920", "1440 × 2560", "自定义宽高"}, saved);
        field("customWidth", "自定义宽度（px，128–8192）", saved, true); field("customHeight", "自定义高度（px，128–8192）", saved, true);
        select("backend", "优先实现方式", new String[]{"main", "virtual"}, new String[]{"修改被控主屏尺寸，失败尝试虚拟显示", "独立虚拟显示，失败尝试修改主屏"}, saved);
        label("虚拟显示是独立桌面，可能与手机当前画面不同；原始尺寸也遵循所选路线。两种方式都失败时使用原始主屏。主屏尺寸覆盖在远控会话关闭时恢复。全屏和小窗均等比完整显示。", false);
        field("virtualApp", "虚拟显示启动应用包名（可留空）", saved, false);
        label("留空时尝试系统默认桌面，再尝试系统设置。虚拟屏中的任务结束后会尝试移回主屏。", false);
        field("maxSize", "视频传输最大边（px，0 不限；默认 1600）", saved, true);
        select("videoCodec", "视频编码格式", StreamSettings.VIDEOS, new String[]{"H.264（默认）", "H.265 / HEVC", "AV1", "VP8", "VP9"}, saved);
        field("videoBitRate", "视频码率（bit/s，默认 6000000）", saved, true); field("fps", "最高帧率（1–240，默认 60）", saved, true);
        select("audioCodec", "音频编码格式", StreamSettings.AUDIOS, new String[]{"PCM（默认，无压缩）", "AAC", "Opus", "FLAC（无损）"}, saved);
        field("audioBitRate", "音频码率（bit/s，16000–512000）", saved, true);
        label("PCM 不使用码率或编解码组件设置；FLAC 的实际码率取决于内容。声音开关仍在连接页。组件不可用会提示并回退 H.264 / PCM。", false);
        LinearLayout advanced = new LinearLayout(context); advanced.setOrientation(VERTICAL); advanced.setVisibility(GONE);
        Button expand = new Button(context); expand.setText("高级：指定编解码组件"); addView(expand); expand.setOnClickListener(v -> advanced.setVisibility(advanced.getVisibility() == GONE ? VISIBLE : GONE));
        for (String[] item : new String[][]{{"videoEncoder", "被控视频编码器"}, {"videoDecoder", "本地视频解码器"}, {"audioEncoder", "被控音频编码器"}, {"audioDecoder", "本地音频解码器"}}) {
            EditText input = makeField(advanced, item[0], item[1] + "（组件名称，留空自动）", saved.get(item[0]), false); fields.put(item[0], input);
        }
        addView(advanced);
        TextView result = label("", false); result.setTag("streamSettingsResult");
        Button save = new Button(context); save.setText("保存分辨率与音视频设置"); save.setTag("saveStreamSettings"); addView(save);
        save.setOnClickListener(v -> {
            try {
                Map<String, String> values = new LinkedHashMap<>();
                for (Map.Entry<String, EditText> e : fields.entrySet()) values.put(e.getKey(), e.getValue().getText().toString().trim());
                for (Map.Entry<String, Spinner> e : choices.entrySet()) values.put(e.getKey(), keys.get(e.getKey())[e.getValue().getSelectedItemPosition()]);
                StreamPreferences.save(context, values); result.setText("已保存；关闭该设备投屏后重新打开生效。现有会话不变。");
            } catch (IllegalArgumentException error) { result.setText("未保存：" + error.getMessage()); }
        });
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
        label(text, false); Spinner spinner = new Spinner(getContext()); spinner.setTag("stream_" + key); spinner.setContentDescription(text);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(getContext(), android.R.layout.simple_spinner_item, labels); adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter); spinner.setSelection(Math.max(0, Arrays.asList(values).indexOf(saved.get(key)))); addView(spinner, new LayoutParams(-1, MainUi.dp(getContext(), 52))); choices.put(key, spinner); keys.put(key, values);
    }
}
