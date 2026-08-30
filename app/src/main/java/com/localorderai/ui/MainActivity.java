package com.localorderai.ui;

import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentTransaction;

import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.localorderai.R;

/**
 * MainActivity
 * -------------
 * الحاوية الرئيسية للتطبيق: بار تنقل سفلي (Bottom Navigation) بثلاث
 * تابات:
 *   1) الرئيسية      -> DashboardFragment (الإعدادات + إضافة طلبات + بدء/إيقاف الحملة)
 *   2) الأرقام الحالية -> LiveCallsFragment (الأرقام اللي بيتم الرن عليها في الحملة الجارية + تحكمات)
 *   3) السجلات        -> LogsFragment (كل الطلبات وتصدير CSV)
 *
 * (قبل كده كان كل تاب Activity منفصلة؛ دلوقتي كلهم Fragments جوه
 * الشاشة دي عشان الـ Bottom Nav يشتغل زي أي تطبيق عادي.)
 */
public class MainActivity extends AppCompatActivity {

    private BottomNavigationView bottomNav;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        bottomNav = findViewById(R.id.bottomNav);

        bottomNav.setOnItemSelectedListener(item -> {
            showFragmentFor(item.getItemId());
            return true;
        });

        if (savedInstanceState == null) {
            bottomNav.setSelectedItemId(R.id.nav_dashboard);
        }
    }

    /** بيسمح لأي Fragment تاني (زي DashboardFragment) إنه يبدّل التاب برمجيًا. */
    public void selectTab(int navItemId) {
        bottomNav.setSelectedItemId(navItemId);
    }

    private void showFragmentFor(int itemId) {
        Fragment fragment;
        if (itemId == R.id.nav_live_calls) {
            fragment = new LiveCallsFragment();
        } else if (itemId == R.id.nav_logs) {
            fragment = new LogsFragment();
        } else {
            fragment = new DashboardFragment();
        }

        FragmentTransaction transaction = getSupportFragmentManager().beginTransaction();
        transaction.replace(R.id.fragmentContainer, fragment);
        transaction.commit();
    }
}
