package io.github.kingslou.memorycontrol.sample;

import android.app.Activity;
import android.os.Bundle;
import android.widget.Button;

/** 使用普通 Activity 演示面板跟随前台页面及保留测试内存。 */
public final class SecondActivity extends Activity {
    /** 返回上一页时应保留压力状态和面板位置。 */
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Button back = new Button(this);
        back.setText("第二个页面：点击返回");
        back.setOnClickListener(view -> finish());
        setContentView(back);
    }
}
