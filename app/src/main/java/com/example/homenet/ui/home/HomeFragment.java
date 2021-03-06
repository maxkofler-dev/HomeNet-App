package com.example.homenet.ui.home;

import android.app.ProgressDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.media.tv.TvInputService;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Surface;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.Observer;
import androidx.lifecycle.ViewModelProviders;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.example.homenet.ExceptionClasses.NoConnectionToWSServer;
import com.example.homenet.R;
import com.example.homenet.ValueView;
import com.example.homenet.network.HNNetworking;
import com.example.homenet.weathersens.WSValueserver;

import org.w3c.dom.Text;

import java.io.CharArrayReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.UnknownHostException;

public class HomeFragment extends Fragment {

    private HomeViewModel homeViewModel;

    private SharedPreferences preferences;
    private SharedPreferences.Editor prefseditor;

    private String ip = "192.168.1.24";
    private int port = 8090;

    private LinearLayout ll;
    private boolean doAutoRefresh;

    int widgets = 0;
    View root;

    ValueView[] vs;
    WSValueserver vServer;

    boolean connectedToServer = false;
    SwipeRefreshLayout swipeRefresh;
    ProgressDialog dialog;

    boolean autoRefreshRunning = false;
    Thread autoRefreshThread;

    public View onCreateView(@NonNull LayoutInflater inflater,
                             ViewGroup container, Bundle savedInstanceState) {
        homeViewModel =
                ViewModelProviders.of(this).get(HomeViewModel.class);
        root = inflater.inflate(R.layout.fragment_home, container, false);


        swipeRefresh = root.findViewById(R.id.swipeRefresh);
        swipeRefresh.setOnRefreshListener(new SwipeRefreshLayout.OnRefreshListener() {
            @Override
            public void onRefresh() {
                swipeRefresh.setRefreshing(true);
                refresh(true, false);
                swipeRefresh.setRefreshing(false);
            }
        });

        return root;
    }

    @Override
    public void onResume() {
        super.onResume();

        final Handler handler = new Handler(Looper.getMainLooper());
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                refresh(true, false);
            }
        }, 200);

        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
            @Override
            public void run() {
                startAutoRefresh();
            }
        }, 200);

    }

    private void startAutoRefresh(){
        if (doAutoRefresh){
            Log.i("homenet-startAutoRefresh()", "Starting autorefreshing of values!");
            if (autoRefreshRunning){
                autoRefreshThread.interrupt();
                autoRefreshRunning = false;
            }
            autoRefreshThread = new Thread(new AutoRefresh());
            autoRefreshThread.start();
            autoRefreshRunning = true;
        }

    }

    @Override
    public void onPause(){
        super.onPause();
        if (autoRefreshRunning)
        {
            autoRefreshThread.interrupt();
            autoRefreshRunning = false;
        }
    }

    private void refresh(boolean waitForEnd, final boolean showLoading){

        class RefreshClass implements Runnable{
            @Override
            public void run() {
                if (showLoading){
                    getActivity().runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            dialog.show();
                        }
                    });
                }

                connectedToServer = false;
                preferences = getActivity().getSharedPreferences(getString(R.string.key_hnSavesFile), Context.MODE_PRIVATE);
                prefseditor = preferences.edit();

                ip = preferences.getString(getString(R.string.key_ServerIP), "192.168.1.24");
                port = preferences.getInt(getString(R.string.key_ServerPort), 8090);

                doAutoRefresh = preferences.getBoolean(getString(R.string.key_autorefresh), false);

                vServer = new WSValueserver(ip, port);
                connectedToServer = vServer.init(false);

                if (connectedToServer){

                    ll = root.findViewById(R.id.ll_values);

                    //Variable to check how many widgets there are to create
                    final int countViews = preferences.getInt(getString(R.string.key_countTiles), 2);

                    loadWidgets(countViews);


                    widgets = countViews;
                }
                vServer.closeNet();
                if (showLoading){
                    getActivity().runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            dialog.hide();
                        }
                    });
                }
            }

        }

        Thread refreshThread = new Thread(new RefreshClass());
        refreshThread.start();
        if (waitForEnd)
        {
            try {
                refreshThread.join();
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
    }

    private void loadWidgets(int countViews){

        //Determine if there are to create new widgets

        //If there are a other ammount of widgets, force the user to restart the app
        if (countViews != widgets && widgets != 0){
            Toast.makeText(getContext(), getString(R.string.pls_restart), Toast.LENGTH_LONG).show();
            //System.exit(0);
        }
        //If there are equal widgets, refresh them
        else{
            if (widgets == 0){
                System.out.println("Creating new widgets!");

                vs = new ValueView[countViews];

                for (int i = 0; i < countViews; i++){
                    vs[i] = new ValueView(getContext(), getScreenOrientation(getContext()));
                    vs[i].initialize(i, ip, port);
                    vs[i].setValues(vServer);
                    final int ix = i;
                    getActivity().runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            ll.addView(vs[ix]);
                        }
                    });

                }
            }else{
                System.out.println("Refreshing old widgets!");
                for (int i = 0; i < countViews; i++){
                    vs[i].initialize(i, ip, port);
                    final int ii = i;
                    getActivity().runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            vs[ii].setValues(vServer);
                        }
                    });

                }
            }
        }
    }

    class AutoRefresh implements Runnable{

        private int sleep = 5;

        @Override
        public void run() {
            try{
                Log.i("homenet-AutoRefresh-run()", "Autorefreshing values in " + sleep + "s interval!");


                while (doAutoRefresh && !Thread.currentThread().isInterrupted()) {
                    Thread.sleep(sleep*1000);
                    refresh(true, false);
                    Log.v("homenet-AutoRefresh-run()", "Refreshed values!");
                }
            }catch(InterruptedException e){
                Log.e("homenet-AutoRefresh-run()", "Thread was interrupted!");
            }

        }
    }

    //0 = Portrait
    //1 = Landscape
    public int getScreenOrientation(Context context){
        int forced = preferences.getInt(getString(R.string.key_homeForceLayout), 0);
        if (forced == 0) {
            final int screenOrientation = ((WindowManager) context.getSystemService(Context.WINDOW_SERVICE)).getDefaultDisplay().getOrientation();
            switch (screenOrientation) {
                case Surface.ROTATION_0:
                    return 0;
                case Surface.ROTATION_90:
                    return 1;
                case Surface.ROTATION_180:
                    return 0;
                default:
                    return 1;
            }
        }else{
            Log.i("homenet-HomeFragment-getScreenOrientation()", "Forced a layout: " + (forced - 1));
            return forced - 1;
        }
    }
}