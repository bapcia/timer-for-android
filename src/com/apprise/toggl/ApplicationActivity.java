package com.apprise.toggl;

import android.app.Activity;

public class ApplicationActivity extends Activity {

  Toggl app;

  @Override
  protected void onResume() {
    app = (Toggl) getApplication();
    if (app.getCurrentUser() == null)
      finish();
    super.onResume();
  }

}
