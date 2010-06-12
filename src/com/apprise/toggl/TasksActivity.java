package com.apprise.toggl;

import java.util.Calendar;
import java.util.LinkedList;

import com.apprise.toggl.remote.SyncService;
import com.apprise.toggl.remote.exception.FailedResponseException;
import com.apprise.toggl.storage.DatabaseAdapter;
import com.apprise.toggl.storage.DatabaseAdapter.Projects;
import com.apprise.toggl.storage.DatabaseAdapter.Tasks;
import com.apprise.toggl.storage.models.User;
import com.apprise.toggl.widget.SectionedAdapter;

import android.app.ListActivity;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.database.Cursor;
import android.os.Bundle;
import android.os.IBinder;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.CursorAdapter;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

public class TasksActivity extends ListActivity {

  private DatabaseAdapter dbAdapter;
  private SyncService syncService;
  private Toggl app;
  private User currentUser;
  private LinkedList<Cursor> taskCursors = new LinkedList<Cursor>();
  
  private static final String TAG = "TasksActivity";
  
  @Override
  public void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    requestWindowFeature(Window.FEATURE_INDETERMINATE_PROGRESS);
    setProgressBarIndeterminate(true);
    setContentView(R.layout.tasks);
    
    app = (Toggl) getApplication();    
    dbAdapter = new DatabaseAdapter(this, app);
    dbAdapter.open();

    Intent intent = new Intent(this, SyncService.class);
    bindService(intent, syncConnection, BIND_AUTO_CREATE);
  }
  
  @Override
  protected void onResume() {
    super.onResume();    
    currentUser = app.getCurrentUser();    
    IntentFilter filter = new IntentFilter(SyncService.SYNC_COMPLETED);
    registerReceiver(updateReceiver, filter);
    adapter.clearSections();
    populateList();
  }
  
  @Override
  protected void onPause() {
    unregisterReceiver(updateReceiver);
    super.onPause();
  }
  
  @Override
  protected void onDestroy() {
    dbAdapter.close();
    unbindService(syncConnection);
    super.onDestroy();
  }
  
  public void populateList() {
    for (Cursor c : taskCursors) {
      stopManagingCursor(c);
      c.close();
    }
    taskCursors.clear();
    
    int taskRetentionDays;
    if (currentUser != null) {
      taskRetentionDays = currentUser.task_retention_days;
    } else {
      taskRetentionDays = -1; // never touch the db
    }
    
    Calendar queryCal = (Calendar) Calendar.getInstance().clone();
    
    for (int i = 0; i <= taskRetentionDays; i++) {
      Cursor tasksCursor = dbAdapter.findTasksForListByDate(queryCal.getTime());

      if (tasksCursor.getCount() > 0) {
        startManagingCursor(tasksCursor);
        taskCursors.add(tasksCursor);
        TasksCursorAdapter cursorAdapter = new TasksCursorAdapter(this, tasksCursor);
        String date = Util.smallDateString(queryCal.getTime());
        String headerText = date + " (" + Util.secondsToHM(getDurationTotal(tasksCursor)) + " h)";
        adapter.addSection(headerText, cursorAdapter);
      }
      else {
        tasksCursor.close();
      }

      queryCal.add(Calendar.DATE, -1);
    }
    
    setListAdapter(adapter);
  }
  
  private long getDurationTotal(Cursor tasksCursor) {
    long duration_total = 0;
    long duration = 0;
    while (tasksCursor.moveToNext()) {
      duration = tasksCursor.getLong(tasksCursor.getColumnIndex(Tasks.DURATION));
      if (duration > 0) duration_total += duration; 
    }
    return duration_total;
  }
  
  @Override
  protected void onListItemClick(ListView l, View v, int position, long id) {
    Intent intent = new Intent(TasksActivity.this, TaskActivity.class);

    Cursor cursor = (Cursor) adapter.getItem(position);
    long clickedTaskId = cursor.getLong(cursor.getColumnIndex(Tasks._ID));
    cursor.close();
    
    intent.putExtra(TaskActivity.TASK_ID, clickedTaskId);
    startActivity(intent);    
  }

  @Override
  public boolean onCreateOptionsMenu(Menu menu) {
    getMenuInflater().inflate(R.menu.tasks_menu, menu);
    return super.onCreateOptionsMenu(menu);
  }   

  @Override
  public boolean onOptionsItemSelected(MenuItem item) {
    switch (item.getItemId()) {
      case R.id.tasks_menu_new_task:
        Intent intent = new Intent(this, TaskActivity.class);
        startActivity(intent);
        return true;
      case R.id.tasks_menu_refresh:
        setProgressBarIndeterminateVisibility(true);
        new Thread(syncAllInBackground).start();
        return true;
      case R.id.tasks_menu_account:
        startActivity(new Intent(this, AccountActivity.class));
        return true;
    }
    return super.onOptionsItemSelected(item);
  }  
  
  protected ServiceConnection syncConnection = new ServiceConnection() {
    
    public void onServiceDisconnected(ComponentName name) {}
    
    public void onServiceConnected(ComponentName name, IBinder serviceBinding) {
      SyncService.SyncBinder binding = (SyncService.SyncBinder) serviceBinding;
      syncService = binding.getService();
    }

  };
  
  protected Runnable refreshTasksInBackground = new Runnable() {
    
    public void run() {
      syncService.syncTasks();
    }
  };
  
  protected Runnable syncAllInBackground = new Runnable() {
    
    public void run() {
      try {
        syncService.syncAll();
      } catch (FailedResponseException e) {
        Log.e(TAG, "FailedResponseException", e);
        runOnUiThread(new Runnable() {
          public void run() {
            Toast.makeText(TasksActivity.this, getString(R.string.sync_failed),
                Toast.LENGTH_SHORT).show(); 
            setProgressBarIndeterminateVisibility(false);            
          }
        });        
      }
    }
  };
  
  protected BroadcastReceiver updateReceiver = new BroadcastReceiver() {
    
    @Override
    public void onReceive(Context context, Intent intent) {
      if (intent.getStringExtra(SyncService.COLLECTION).equals(SyncService.ALL_COMPLETED)) {
        adapter.clearSections();
        populateList();
        setProgressBarIndeterminateVisibility(false);      
      }
    }
  };

  SectionedAdapter adapter = new SectionedAdapter() {
    protected View getHeaderView(String caption, int index, View convertView, ViewGroup parent) {
      LinearLayout result = (LinearLayout) convertView;

      if (convertView == null) {
        result = (LinearLayout) getLayoutInflater().inflate(R.layout.tasks_group_header, null);
      }

      TextView header = (TextView) result.findViewById(R.id.task_list_header_text);      
      header.setText(caption);

      return result;
    }
  };
  
  private class TasksCursorAdapter extends CursorAdapter {

    public TasksCursorAdapter(Context context, Cursor cursor) {
      super(context, cursor);
    }

    @Override
    public void bindView(View view, Context context, Cursor cursor) {
      TextView durationView = (TextView) view.findViewById(R.id.task_item_duration);
      long seconds = cursor.getLong(cursor.getColumnIndex(Tasks.DURATION));
      durationView.setText(Util.secondsToHMS(seconds));

      TextView descriprionView = (TextView) view.findViewById(R.id.task_item_description);
      String description = cursor.getString(cursor.getColumnIndex(Tasks.DESCRIPTION));
      if (description == null) {
        descriprionView.setText(R.string.no_description);
        descriprionView.setTextColor(R.color.light_gray);
      } else {
        descriprionView.setText(description);  
      }
      
      TextView clientProjectNameView = (TextView) view.findViewById(R.id.task_item_client_project_name);
      String clientProjectName = cursor.getString(cursor.getColumnIndex(Projects.CLIENT_PROJECT_NAME));
      clientProjectNameView.setText(clientProjectName);
    }

    @Override
    public View newView(Context context, Cursor cursor, ViewGroup parent) {
      View view = getLayoutInflater().inflate(R.layout.task_item, null);
      bindView(view, context, cursor);
      return view;
    }
  }  

}
