package io.github.kingslou.memorycontrol.sample;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import io.github.kingslou.memorycontrol.MemoryPressureFloatingPanel;

/** 提供跨页面和卸载入口，便于手动观察面板生命周期。 */
public final class MainActivity extends Activity {
    /** 创建示例操作页；加压参数通过悬浮面板设置。 */
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(24, 80, 24, 24);
        TextView help = new TextView(this);
        help.setText("MemoryControlAndroid\n\n点击悬浮面板展开，拖动标题移动。\n暂停、收起和切页保留测试内存，点击释放清除。\nAndroid 6/7 仅支持 Java 堆加压。");
        content.addView(help);
        Button next = new Button(this);
        next.setText("打开第二个页面");
        next.setOnClickListener(view -> startActivity(new Intent(this, SecondActivity.class)));
        content.addView(next);
        Button uninstall = new Button(this);
        uninstall.setText("卸载面板并释放测试内存");
        uninstall.setOnClickListener(view -> MemoryPressureFloatingPanel.uninstall());
        content.addView(uninstall);
        setContentView(content);
    }
}
