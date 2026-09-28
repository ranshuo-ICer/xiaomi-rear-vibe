package com.dsh.hello;

import android.app.Activity;
import android.content.res.Configuration;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class MainActivity extends Activity {

    private int count = 0;
    private TextView counter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        boolean landscape = getResources().getConfiguration().orientation
                == Configuration.ORIENTATION_LANDSCAPE;

        LinearLayout root = new LinearLayout(this);
        root.setBackgroundColor(Color.parseColor("#0F172A"));
        root.setGravity(Gravity.CENTER);

        if (landscape) {
            // Rear screen (976x596): two compact columns side by side.
            root.setOrientation(LinearLayout.HORIZONTAL);
            root.setPadding(40, 24, 40, 24);

            LinearLayout left = new LinearLayout(this);
            left.setOrientation(LinearLayout.VERTICAL);
            left.setGravity(Gravity.CENTER_VERTICAL);
            LinearLayout.LayoutParams lpLeft = new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.MATCH_PARENT, 1f);
            left.setLayoutParams(lpLeft);
            left.addView(title(20));
            left.addView(info(11));
            LinearLayout.LayoutParams fLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            fLp.topMargin = 10;
            TextView foot = footer();
            foot.setLayoutParams(fLp);
            left.addView(foot);
            root.addView(left);

            LinearLayout right = new LinearLayout(this);
            right.setOrientation(LinearLayout.VERTICAL);
            right.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams lpRight = new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.MATCH_PARENT, 1f);
            right.setLayoutParams(lpRight);
            right.addView(counter(52));
            LinearLayout.LayoutParams bLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            bLp.topMargin = 8;
            right.addView(button(), bLp);
            root.addView(right);
        } else {
            root.setOrientation(LinearLayout.VERTICAL);
            root.setPadding(64, 96, 64, 96);
            root.addView(title(30));
            root.addView(info(15));
            root.addView(counter(72));
            LinearLayout.LayoutParams bLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            bLp.topMargin = 32;
            bLp.gravity = Gravity.CENTER_HORIZONTAL;
            root.addView(button(), bLp);
            LinearLayout.LayoutParams fLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            fLp.topMargin = 64;
            TextView foot = footer();
            foot.setLayoutParams(fLp);
            root.addView(foot);
        }

        setContentView(root);
    }

    private TextView title(float sp) {
        TextView t = new TextView(this);
        t.setText("Hello from DSH \ud83e\udde0");
        t.setTextSize(sp);
        t.setTextColor(Color.parseColor("#7DD3FC"));
        t.setGravity(Gravity.CENTER);
        return t;
    }

    private TextView info(float sp) {
        TextView t = new TextView(this);
        String stamp = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date());
        t.setText("Built by hand \u2014 no Gradle\n"
                + android.os.Build.MODEL + " \u00b7 Android " + android.os.Build.VERSION.RELEASE + "\n"
                + stamp);
        t.setTextSize(sp);
        t.setTextColor(Color.parseColor("#94A3B8"));
        t.setGravity(Gravity.CENTER);
        t.setPadding(0, 12, 0, 0);
        return t;
    }

    private TextView counter(float sp) {
        counter = new TextView(this);
        counter.setText("0");
        counter.setTextSize(sp);
        counter.setTextColor(Color.WHITE);
        counter.setGravity(Gravity.CENTER);
        counter.setPadding(0, 12, 0, 0);
        return counter;
    }

    private Button button() {
        Button b = new Button(this);
        b.setText("TAP +1");
        b.setTextSize(16);
        b.setPadding(40, 12, 40, 12);
        b.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                count++;
                counter.setText(String.valueOf(count));
            }
        });
        return b;
    }

    private TextView footer() {
        TextView t = new TextView(this);
        t.setText("watched live in the DSH panel \u2728");
        t.setTextSize(10);
        t.setTextColor(Color.parseColor("#475569"));
        t.setGravity(Gravity.CENTER);
        return t;
    }
}
