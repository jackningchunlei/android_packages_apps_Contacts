/*
 * Copyright (C) 2007 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.contacts;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.content.res.Resources;
import android.content.ComponentName;
import android.content.pm.ActivityInfo;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.Drawable;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.os.Vibrator;
import android.provider.Contacts.Intents.Insert;
import android.provider.Contacts.People;
import android.provider.Contacts.Phones;
import android.provider.Contacts.PhonesColumns;
import android.provider.Settings;
import android.telephony.PhoneNumberFormattingTextWatcher;
import android.telephony.PhoneNumberUtils;
import android.telephony.PhoneStateListener;
import android.telephony.TelephonyManager;
import android.preference.PreferenceManager;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.text.method.DialerKeyListener;
import android.util.Log;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.ImageButton;
import android.view.MenuItem.OnMenuItemClickListener;
import com.android.internal.telephony.ITelephony;
import android.content.res.Configuration;


/**
 * Dialer activity that displays the typical twelve key interface.
 */
public class TwelveKeyDialer extends Activity implements View.OnClickListener,
        View.OnLongClickListener, View.OnKeyListener,
        AdapterView.OnItemClickListener, TextWatcher {

    private static final String TAG = "TwelveKeyDialer";
    
    private static final int STOP_TONE = 1;

    /** The length of DTMF tones in milliseconds */
    private static final int TONE_LENGTH_MS = 150;
    
    /** The DTMF tone volume relative to other sounds in the stream */
    private static final int TONE_RELATIVE_VOLUME = 50;

    private EditText mDigits;
    private MenuItem mAddToContactMenuItem, mSmsMenuItem, mPreferences;
    private ToneGenerator mToneGenerator;
    private Object mToneGeneratorLock = new Object();
    private Drawable mDigitsBackground;
    private Drawable mDigitsEmptyBackground;
    private View mDialpad;
    private View mVoicemailDialAndDeleteRow;
    private ImageButton mVoicemailButton, mDialButton, mDelete;
    private SharedPreferences prefs;    
    private Vibrator vibrator; 

    private ListView mDialpadChooser;
    private DialpadChooserAdapter mDialpadChooserAdapter;

    // determines if we want to playback local DTMF tones.
    private boolean mDTMFToneEnabled;
    
    /** Identifier for the "Add Call" intent extra. */
    static final String ADD_CALL_MODE_KEY = "add_call_mode";
    /** Indicates if we are opening this dialer to add a call from the InCallScreen. */
    private boolean mIsAddCallMode;    

    PhoneStateListener mPhoneStateListener = new PhoneStateListener() {
            /**
             * Listen for phone state changes so that we can take down the
             * "dialpad chooser" if the phone becomes idle while the
             * chooser UI is visible.
             */
            @Override
            public void onCallStateChanged(int state, String incomingNumber) {
                // Log.i(TAG, "PhoneStateListener.onCallStateChanged: "
                //       + state + ", '" + incomingNumber + "'");
                if ((state == TelephonyManager.CALL_STATE_IDLE) && dialpadChooserVisible()) {
                    // Log.i(TAG, "Call ended with dialpad chooser visible!  Taking it down...");
                    // Note there's a race condition in the UI here: the
                    // dialpad chooser could conceivably disappear (on its
                    // own) at the exact moment the user was trying to select
                    // one of the choices, which would be confusing.  (But at
                    // least that's better than leaving the dialpad chooser
                    // onscreen, but useless...)
                    showDialpadChooser(false);
                }
            }
        };

    public void beforeTextChanged(CharSequence s, int start, int count, int after) {
        // Do nothing
    }

    public void onTextChanged(CharSequence input, int start, int before, int changeCount) {
        // Do nothing
        // DTMF Tones do not need to be played here any longer - 
        // the DTMF dialer handles that functionality now.
    }

    public void afterTextChanged(Editable input) {
        if (SpecialCharSequenceMgr.handleChars(this, input.toString(), mDigits)) {
            // A special sequence was entered, clear the digits
            mDigits.getText().clear();
        }

        // Set the proper background for the dial input area
        if (mDigits.length() != 0) {
            mDigits.setBackgroundDrawable(mDigitsBackground);
        } else {
            mDigits.setBackgroundDrawable(mDigitsEmptyBackground);
        }
    }

    @Override
    protected void onCreate(Bundle icicle) {
        super.onCreate(icicle);
        
        prefs = PreferenceManager.getDefaultSharedPreferences(getBaseContext());

        // Set the content view
        setContentView(getContentViewResource());

        // Load up the resources for the text field and delete button
        Resources r = getResources();
        mDigitsBackground = r.getDrawable(R.drawable.btn_dial_textfield_active);
        mDigitsEmptyBackground = r.getDrawable(R.drawable.btn_dial_textfield);

        mDigits = (EditText) findViewById(R.id.digits);
        mDigits.setKeyListener(DialerKeyListener.getInstance());
        mDigits.setOnClickListener(this);
        mDigits.setOnKeyListener(this);
        mDigits.setCursorVisible(false);
        setDigitsColor();
        maybeAddNumberFormatting();

        // Check for the presence of the keypad
        View view = findViewById(R.id.one);
        if (view != null) {
            setupKeypad();
        }

        mVoicemailDialAndDeleteRow = findViewById(R.id.voicemailAndDialAndDelete);        

        // Check whether we should show the onscreen "Dial" button.
        mDialButton = (ImageButton)mVoicemailDialAndDeleteRow.findViewById(R.id.dialButton);

//      if (r.getBoolean(R.bool.config_show_onscreen_dial_button)) {
            mDialButton.setOnClickListener(this);
            mDialButton.setOnLongClickListener(this);
//      } else {
//          mDialButton.setVisibility(View.GONE); // It's VISIBLE by default
//          mDialButton = null;
//      }

        mDelete = (ImageButton)mVoicemailDialAndDeleteRow.findViewById(R.id.deleteButton);
        mDelete.setOnClickListener(this);
        mDelete.setOnLongClickListener(this);

        mDialpad = (View) findViewById(R.id.dialpad);  // This is null in landscape mode

        mDigits.setInputType(android.text.InputType.TYPE_NULL);

        // Set up the "dialpad chooser" UI; see showDialpadChooser().
        mDialpadChooser = (ListView) findViewById(R.id.dialpadChooser);
        mDialpadChooser.setOnItemClickListener(this);

        if (!resolveIntent() && icicle != null) {
            super.onRestoreInstanceState(icicle);
        }
        
        //Wysie_Soh: I shifted this down, otherwise the "Add" button won't be activated when trying to add a number directly (say, from a message).
        //In other words, DialtactsActivity is called directly, and Dialer is initiated with a number already present.
        initVoicemailButton();
        checkForNumber();
        
        //Wysie_Soh: Initialize vibrator
        vibrator = (Vibrator)getSystemService(Context.VIBRATOR_SERVICE);

        // If the mToneGenerator creation fails, just continue without it.  It is
        // a local audio signal, and is not as important as the dtmf tone itself.
        synchronized (mToneGeneratorLock) {
            if (mToneGenerator == null) {
                try {
                    mToneGenerator = new ToneGenerator(AudioManager.STREAM_RING,
                            TONE_RELATIVE_VOLUME);
                } catch (RuntimeException e) {
                    Log.w(TAG, "Exception caught while creating local tone generator: " + e);
                    mToneGenerator = null;
                }
            }
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        synchronized(mToneGeneratorLock) {
            if (mToneGenerator != null) {
                mToneStopper.removeMessages(STOP_TONE);
                mToneGenerator.release();
                mToneGenerator = null;
            }
        }
    }

    @Override
    protected void onRestoreInstanceState(Bundle icicle) {
        // Do nothing, state is restored in onCreate() if needed
    }
    
    protected void maybeAddNumberFormatting() {
        mDigits.addTextChangedListener(new PhoneNumberFormattingTextWatcher());
    }
    
    /**
     * Overridden by subclasses to control the resource used by the content view. 
     */
    protected int getContentViewResource() {
        return R.layout.twelve_key_dialer;
    }

    private boolean resolveIntent() {
        boolean ignoreState = false;

        // Find the proper intent
        final Intent intent;
        if (isChild()) {
            intent = getParent().getIntent();
            ignoreState = intent.getBooleanExtra(DialtactsActivity.EXTRA_IGNORE_STATE, false);
        } else {
            intent = getIntent();
        }
        // Log.i(TAG, "==> resolveIntent(): intent: " + intent);

        // by default we are not adding a call.
        mIsAddCallMode = false;

        // By default we don't show the "dialpad chooser" UI.
        boolean needToShowDialpadChooser = false;

        // Resolve the intent
        final String action = intent.getAction();
        if (Intent.ACTION_DIAL.equals(action) || Intent.ACTION_VIEW.equals(action)) {
            // see if we are "adding a call" from the InCallScreen; false by default.
            mIsAddCallMode = intent.getBooleanExtra(ADD_CALL_MODE_KEY, false);
            Uri uri = intent.getData();
            if (uri != null) {
                if ("tel".equals(uri.getScheme())) {
                    // Put the requested number into the input area
                    String data = uri.getSchemeSpecificPart();
                    setFormattedDigits(data);
                } else {
                    String type = intent.getType();
                    if (People.CONTENT_ITEM_TYPE.equals(type)
                            || Phones.CONTENT_ITEM_TYPE.equals(type)) {
                        // Query the phone number
                        Cursor c = getContentResolver().query(intent.getData(),
                                new String[] {PhonesColumns.NUMBER}, null, null, null);
                        if (c != null) {
                            if (c.moveToFirst()) {
                                // Put the number into the input area
                                setFormattedDigits(c.getString(0));
                            }
                            c.close();
                        }
                    }
                }
            }
        } else if (Intent.ACTION_MAIN.equals(action)) {
            // The MAIN action means we're bringing up a blank dialer
            // (e.g. by selecting the Home shortcut, or tabbing over from
            // Contacts or Call log.)
            //
            // At this point, IF there's already an active call, there's a
            // good chance that the user got here accidentally (but really
            // wanted the in-call dialpad instead).  So we bring up an
            // intermediate UI to make the user confirm what they really
            // want to do.
            if (phoneIsInUse()) {
                // Log.i(TAG, "resolveIntent(): phone is in use; showing dialpad chooser!");
                needToShowDialpadChooser = true;
            }
        }

        // Bring up the "dialpad chooser" IFF we need to make the user
        // confirm which dialpad they really want.
        showDialpadChooser(needToShowDialpadChooser);

        return ignoreState;
    }

    protected void setFormattedDigits(String data) {
        // strip the non-dialable numbers out of the data string.
        String dialString = PhoneNumberUtils.extractNetworkPortion(data);
        dialString = PhoneNumberUtils.formatNumber(dialString);
        if (!TextUtils.isEmpty(dialString)) {
            Editable digits = mDigits.getText();
            digits.replace(0, digits.length(), dialString);
            // for some reason this isn't getting called in the digits.replace call above..
            // but in any case, this will make sure the background drawable looks right
            afterTextChanged(digits);
        }
    }

    @Override
    protected void onNewIntent(Intent newIntent) {
        setIntent(newIntent);
        resolveIntent();
    }
    
    @Override
    protected void onPostCreate(Bundle savedInstanceState) {
        super.onPostCreate(savedInstanceState);

        // This can't be done in onCreate(), since the auto-restoring of the digits
        // will play DTMF tones for all the old digits if it is when onRestoreSavedInstanceState()
        // is called. This method will be called every time the activity is created, and
        // will always happen after onRestoreSavedInstanceState().
        mDigits.addTextChangedListener(this);
    }
    
    private void setupKeypad() {
        // Setup the listeners for the buttons        
        ImageButton dialOne = (ImageButton)findViewById(R.id.one);
        dialOne.setOnClickListener(this);
        dialOne.setOnLongClickListener(this);
        
        if (hasVoicemail()) {
        	dialOne.setImageResource(R.drawable.dial_num_1_with_vm);
        }
        else {
        	dialOne.setImageResource(R.drawable.dial_num_1_no_vm);
        }

        findViewById(R.id.two).setOnClickListener(this);
        findViewById(R.id.three).setOnClickListener(this);
        findViewById(R.id.four).setOnClickListener(this);
        findViewById(R.id.five).setOnClickListener(this);
        findViewById(R.id.six).setOnClickListener(this);
        findViewById(R.id.seven).setOnClickListener(this);
        findViewById(R.id.eight).setOnClickListener(this);
        findViewById(R.id.nine).setOnClickListener(this);
        findViewById(R.id.star).setOnClickListener(this);

        View view = findViewById(R.id.zero);
        view.setOnClickListener(this);
        view.setOnLongClickListener(this);

        findViewById(R.id.pound).setOnClickListener(this);
    }

    @Override
    protected void onResume() {
        super.onResume();

        // retrieve the DTMF tone play back setting.
        mDTMFToneEnabled = Settings.System.getInt(getContentResolver(),
                Settings.System.DTMF_TONE_WHEN_DIALING, 1) == 1;

        // if the mToneGenerator creation fails, just continue without it.  It is 
        // a local audio signal, and is not as important as the dtmf tone itself.
        synchronized(mToneGeneratorLock) {
            if (mToneGenerator == null) {
                try {
                    mToneGenerator = new ToneGenerator(AudioManager.STREAM_RING,
                            TONE_RELATIVE_VOLUME);
                } catch (RuntimeException e) {
                    Log.w(TAG, "Exception caught while creating local tone generator: " + e);
                    mToneGenerator = null;
                }
            }
        }
        
        Activity parent = getParent();
        // See if we were invoked with a DIAL intent. If we were, fill in the appropriate
        // digits in the dialer field.
        if (parent != null && parent instanceof DialtactsActivity) {
            Uri dialUri = ((DialtactsActivity) parent).getAndClearDialUri();
            if (dialUri != null) {
                resolveIntent();
            }
        }

        // While we're in the foreground, listen for phone state changes,
        // purely so that we can take down the "dialpad chooser" if the
        // phone becomes idle while the chooser UI is visible.
        TelephonyManager telephonyManager = (TelephonyManager) getSystemService(TELEPHONY_SERVICE);
        telephonyManager.listen(mPhoneStateListener, PhoneStateListener.LISTEN_CALL_STATE);

        // Potentially show hint text in the mDigits field when the user
        // hasn't typed any digits yet.  (If there's already an active call,
        // this hint text will remind the user that he's about to add a new
        // call.)
        //
        // TODO: consider adding better UI for the case where *both* lines
        // are currently in use.  (Right now we let the user try to add
        // another call, but that call is guaranteed to fail.  Perhaps the
        // entire dialer UI should be disabled instead.)
        if (phoneIsInUse()) {
            mDigits.setHint(R.string.dialerDialpadHintText);
        } else {
            // Common case; no hint necessary.
            mDigits.setHint(null);

            // Also, a sanity-check: the "dialpad chooser" UI should NEVER
            // be visible if the phone is idle!
            showDialpadChooser(false);
        }
        
        updateDialer();        
    }
    
    //Wysie_Soh: Handle update of dialer after exiting settings
    private void updateDialer() {
    	initVoicemailButton();
    	checkForNumber();
    	setDigitsColor();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        if (hasFocus) {
            // Hide soft keyboard, if visible (it's fugly over button dialer).
            // The only known case where this will be true is when launching the dialer with
            // ACTION_DIAL via a soft keyboard.  we dismiss it here because we don't
            // have a window token yet in onCreate / onNewIntent
            InputMethodManager inputMethodManager = (InputMethodManager)
                    getSystemService(Context.INPUT_METHOD_SERVICE);
            inputMethodManager.hideSoftInputFromWindow(mDigits.getWindowToken(), 0);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();

        // Stop listening for phone state changes.
        TelephonyManager telephonyManager = (TelephonyManager) getSystemService(TELEPHONY_SERVICE);
        telephonyManager.listen(mPhoneStateListener, PhoneStateListener.LISTEN_NONE);

        synchronized(mToneGeneratorLock) {
            if (mToneGenerator != null) {
                mToneStopper.removeMessages(STOP_TONE);
                mToneGenerator.release();
                mToneGenerator = null;
            }
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        mAddToContactMenuItem = menu.add(0, 0, 0, R.string.recentCalls_addToContact).setIcon(android.R.drawable.ic_menu_add);
        mSmsMenuItem = menu.add(0, 1, 0, R.string.dialer_menu_sms).setIcon(R.drawable.ic_menu_smsmms);
	mPreferences = menu.add(0, 2, 0, R.string.menu_preferences).setIcon(android.R.drawable.ic_menu_preferences);

        return true;
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        // We never show a menu if the "choose dialpad" UI is up.
        if (dialpadChooserVisible()) {
            return false;
        }

        CharSequence digits = mDigits.getText();
        
        
        //Wysie_Soh
        //If digits are empty and disable number check is not enabled OR left button is set to addtocontact, hide addtocontact from menu
        if (((digits == null || !TextUtils.isGraphic(digits)) && !prefs.getBoolean("dial_disable_num_check", false))
        	|| prefs.getString("vm_button", "0").equals("0")) {
            mAddToContactMenuItem.setVisible(false);
        } else {
            // Put the current digits string into an intent
            Intent intent = new Intent(Intent.ACTION_INSERT_OR_EDIT);
            intent.putExtra(Insert.PHONE, mDigits.getText());
            intent.setType(People.CONTENT_ITEM_TYPE);
            mAddToContactMenuItem.setIntent(intent);
            mAddToContactMenuItem.setVisible(true);
        }
        
        //Wysie_Soh: Preferences intent
        Intent dialPrefs = new Intent(this, ContactsPreferences.class);
        mPreferences.setIntent(dialPrefs);        
        
        //Wysie_Soh
        //If digits are empty and disable number check is not enabled OR left button is set to sms, hide sms from menu
        if (((digits == null || !TextUtils.isGraphic(digits)) && !prefs.getBoolean("dial_disable_num_check", false))
        	|| prefs.getString("vm_button", "0").equals("1")) {
        	mSmsMenuItem.setVisible(false);
        }
        else {
        	mSmsMenuItem.setOnMenuItemClickListener(new OnMenuItemClickListener() {
        		public boolean onMenuItemClick(MenuItem item) {
				smsToNumber((mDigits.getText()).toString());
				return true;
			}
		});
		mSmsMenuItem.setVisible(true);
	}
        
        return true;
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_CALL: {
                long callPressDiff = SystemClock.uptimeMillis() - event.getDownTime();
                if (callPressDiff >= ViewConfiguration.getLongPressTimeout()) {
                    // Launch voice dialer
                    Intent intent = new Intent(Intent.ACTION_VOICE_COMMAND);
                    intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    try {
                        startActivity(intent);
                    } catch (ActivityNotFoundException e) {
                    }
                }
                return true;
            }
            case KeyEvent.KEYCODE_1: {
                long timeDiff = SystemClock.uptimeMillis() - event.getDownTime(); 
                if (timeDiff >= ViewConfiguration.getLongPressTimeout()) {
                    // Long press detected, call voice mail
                    callVoicemail();
                }
                return true;
            }
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_CALL: {
                if (mIsAddCallMode && (TextUtils.isEmpty(mDigits.getText().toString()))) {
                    // if we are adding a call from the InCallScreen and the phone
                    // number entered is empty, we just close the dialer to expose
                    // the InCallScreen under it.
                    finish();
                } else {
                    // otherwise, we place the call.
                    placeCall();
                }
                return true;
            }
        }
        return super.onKeyUp(keyCode, event);
    }
    
    private void keyPressed(int keyCode) {
        KeyEvent event = new KeyEvent(KeyEvent.ACTION_DOWN, keyCode);
        mDigits.onKeyDown(keyCode, event);
    }

    public boolean onKey(View view, int keyCode, KeyEvent event) {
        switch (view.getId()) {
            case R.id.digits:
                if (keyCode == KeyEvent.KEYCODE_ENTER) {
                    placeCall();
                    return true;
                }
                break;
        }
        return false;
    }

    public void onClick(View view) {
        if (prefs.getBoolean("dial_enable_haptic", false)) {
            vibrator.vibrate(30);
        }
        
        switch (view.getId()) {
            case R.id.one: {
                playTone(ToneGenerator.TONE_DTMF_1);
                keyPressed(KeyEvent.KEYCODE_1);
                break;
            }
            case R.id.two: {
                playTone(ToneGenerator.TONE_DTMF_2);
                keyPressed(KeyEvent.KEYCODE_2);
                break;
            }
            case R.id.three: {
                playTone(ToneGenerator.TONE_DTMF_3);
                keyPressed(KeyEvent.KEYCODE_3);
                break;
            }
            case R.id.four: {
                playTone(ToneGenerator.TONE_DTMF_4);
                keyPressed(KeyEvent.KEYCODE_4);
                break;
            }
            case R.id.five: {
                playTone(ToneGenerator.TONE_DTMF_5);
                keyPressed(KeyEvent.KEYCODE_5);
                break;
            }
            case R.id.six: {
                playTone(ToneGenerator.TONE_DTMF_6);
                keyPressed(KeyEvent.KEYCODE_6);
                break;
            }
            case R.id.seven: {
                playTone(ToneGenerator.TONE_DTMF_7);
                keyPressed(KeyEvent.KEYCODE_7);
                break;
            }
            case R.id.eight: {
                playTone(ToneGenerator.TONE_DTMF_8);
                keyPressed(KeyEvent.KEYCODE_8);
                break;
            }
            case R.id.nine: {
                playTone(ToneGenerator.TONE_DTMF_9);
                keyPressed(KeyEvent.KEYCODE_9);
                break;
            }
            case R.id.zero: {
                playTone(ToneGenerator.TONE_DTMF_0);
                keyPressed(KeyEvent.KEYCODE_0);
                break;
            }
            case R.id.pound: {
                playTone(ToneGenerator.TONE_DTMF_P);
                keyPressed(KeyEvent.KEYCODE_POUND);
                break;
            }
            case R.id.star: {
                playTone(ToneGenerator.TONE_DTMF_S);
                keyPressed(KeyEvent.KEYCODE_STAR);
                break;
            }
            case R.id.deleteButton: {
                keyPressed(KeyEvent.KEYCODE_DEL);
                break;
            }
            case R.id.dialButton: {
                placeCall();
                break;
            }
            case R.id.voicemailButton: {
            	if (prefs.getString("vm_button", "0").equals("0")) {
            		addToContacts();
            	}
            	else if (prefs.getString("vm_button", "0").equals("1")) {
            		smsToNumber((mDigits.getText()).toString());
            	}
            	else if (prefs.getString("vm_button", "0").equals("2")) {
            		callVoicemail();
            	}
            	break;
            }
            case R.id.digits: {
                if (mDigits.length() != 0) {
                    mDigits.setCursorVisible(true);
                }
                break;
            }
        }
        
        //Wysie_Soh
        //Set the "voicemail"/add button to be enabled/disabled according to if any number is displayed   
        checkForNumber();
    }

    public boolean onLongClick(View view) {
        if (prefs.getBoolean("dial_enable_haptic", false)) {
            vibrator.vibrate(50);
        }
        
        Editable digits = mDigits.getText();
        int id = view.getId();
        switch (id) {
            case R.id.deleteButton: {
                digits.clear();
                checkForNumber();              
                return true;
            }
            case R.id.dialButton: {
            	Intent intent = new Intent(Intent.ACTION_VOICE_COMMAND);
            	intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            	
            	try {
                        startActivity(intent);
                        return true;
                } catch (ActivityNotFoundException e) {
                	return false;
                }

            }
            case R.id.one: {
                if (digits.length() == 0) {
                    if (hasVoicemail()) {
                        if (prefs.getBoolean("vm_use_1_for_regular_vm", false)) {
                            Intent intent = new Intent(Intent.ACTION_CALL_PRIVILEGED, Uri.fromParts("voicemail", "", null));
                            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                            startActivity(intent);
                        }
                        else {
                        	callVoicemail();
                        }
                    	return true;
                    }
                }
                return false;
            }
            case R.id.zero: {
                keyPressed(KeyEvent.KEYCODE_PLUS);
                return true;
            }
        }
        return false;
    }
    
    //Wysie_Soh
    void smsToNumber(String number) {
    	Intent sendIntent = new Intent(Intent.ACTION_VIEW, Uri.parse("sms://"));
    	sendIntent.putExtra("address", number);
    	sendIntent.putExtra("sms_body", "");
    	startActivity(sendIntent); 
    }
    
    //Wysie_Soh    
    void addToContacts() {
    	// Put the current digits string into an intent
    	Intent intent = new Intent(Intent.ACTION_INSERT_OR_EDIT);
    	intent.putExtra(Insert.PHONE, mDigits.getText());
    	intent.setType(People.CONTENT_ITEM_TYPE);
    	startActivity(intent);
    }
    
    /*
    void callVoicemail() {
        Intent intent = new Intent(Intent.ACTION_MAIN);
        ComponentName component = new ComponentName(DialerSettings.getLeftButtonIntentPkg(this), DialerSettings.getLeftButtonIntentCls(this));
        intent.setComponent(component);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(intent);
        } catch (ActivityNotFoundException e) {
            intent = new Intent(Intent.ACTION_CALL_PRIVILEGED, Uri.fromParts("voicemail", "", null));
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        }
        mDigits.getText().clear();
        finish();
    }
    */
    
    void callVoicemail() {
        Intent intent;
        String vmHandler = prefs.getString("vm_handler", "0");
        if (vmHandler.equals("0")) {
            intent = new Intent(Intent.ACTION_CALL_PRIVILEGED,
                        Uri.fromParts("voicemail", "", null));
        } else {
            String[] cmp = vmHandler.split("/");
            intent = new Intent(Intent.ACTION_MAIN);
            Log.d(TAG, vmHandler);
            ComponentName component = new ComponentName(cmp[0], cmp[0] + cmp[1]);
            intent.setComponent(component);
        }
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(intent);
        mDigits.getText().clear();
        //finish();
    }

    void placeCall() {
        final String number = mDigits.getText().toString();
        if (number == null || !TextUtils.isGraphic(number)) {
            // There is no number entered.
            playTone(ToneGenerator.TONE_PROP_NACK);
            return;
        }
        Intent intent = new Intent(Intent.ACTION_CALL_PRIVILEGED,
                Uri.fromParts("tel", number, null));
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(intent);
        mDigits.getText().clear();
        finish();
    }

    Handler mToneStopper = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case STOP_TONE:
                    synchronized(mToneGeneratorLock) {
                        if (mToneGenerator == null) {
                            Log.w(TAG, "mToneStopper: mToneGenerator == null");
                        } else {
                            mToneGenerator.stopTone();
                        }
                    }
                    break;
            }
        }
    };

    /**
     * Plays the specified tone for TONE_LENGTH_MS milliseconds.
     *
     * The tone is played locally, using the audio stream for phone calls.
     * Tones are played only if the "Audible touch tones" user preference
     * is checked, and are NOT played if the device is in silent mode.
     *
     * @param tone a tone code from {@link ToneGenerator}
     */
    void playTone(int tone) {
        // if local tone playback is disabled, just return.
        if (!mDTMFToneEnabled) {
            return;
        }

        // Also do nothing if the phone is in silent mode.
        // We need to re-check the ringer mode for *every* playTone()
        // call, rather than keeping a local flag that's updated in
        // onResume(), since it's possible to toggle silent mode without
        // leaving the current activity (via the ENDCALL-longpress menu.)
        AudioManager audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        int ringerMode = audioManager.getRingerMode();
        if ((ringerMode == AudioManager.RINGER_MODE_SILENT)
            || (ringerMode == AudioManager.RINGER_MODE_VIBRATE)) {
            return;
        }

        synchronized(mToneGeneratorLock) {
            if (mToneGenerator == null) {
                Log.w(TAG, "playTone: mToneGenerator == null, tone: "+tone);
                return;
            }
            
            // Remove pending STOP_TONE messages
            mToneStopper.removeMessages(STOP_TONE);
    
            // Start the new tone (will stop any playing tone)
            mToneGenerator.startTone(tone);
            mToneStopper.sendEmptyMessageDelayed(STOP_TONE, TONE_LENGTH_MS);
        }
    }

    /**
     * Brings up the "dialpad chooser" UI in place of the usual Dialer
     * elements (the textfield/button and the dialpad underneath).
     *
     * We show this UI if the user brings up the Dialer while a call is
     * already in progress, since there's a good chance we got here
     * accidentally (and the user really wanted the in-call dialpad instead).
     * So in this situation we display an intermediate UI that lets the user
     * explicitly choose between the in-call dialpad ("Use touch tone
     * keypad") and the regular Dialer ("Add call").  (Or, the option "Return
     * to call in progress" just goes back to the in-call UI with no dialpad
     * at all.)
     *
     * @param enabled If true, show the "dialpad chooser" instead
     *                of the regular Dialer UI
     */
    private void showDialpadChooser(boolean enabled) {
        if (enabled) {
            // Log.i(TAG, "Showing dialpad chooser!");
            mDigits.setVisibility(View.GONE);
            if (mDialpad != null) mDialpad.setVisibility(View.GONE);
            mDialpadChooser.setVisibility(View.VISIBLE);

            // Instantiate the DialpadChooserAdapter and hook it up to the
            // ListView.  We do this only once.
            if (mDialpadChooserAdapter == null) {
                mDialpadChooserAdapter = new DialpadChooserAdapter(this);
                mDialpadChooser.setAdapter(mDialpadChooserAdapter);
            }
        } else {
            // Log.i(TAG, "Displaying normal Dialer UI.");
            mDigits.setVisibility(View.VISIBLE);
            if (mDialpad != null) mDialpad.setVisibility(View.VISIBLE);
            mDialpadChooser.setVisibility(View.GONE);
        }
    }

    /**
     * @return true if we're currently showing the "dialpad chooser" UI.
     */
    private boolean dialpadChooserVisible() {
        return mDialpadChooser.getVisibility() == View.VISIBLE;
    }

    /**
     * Simple list adapter, binding to an icon + text label
     * for each item in the "dialpad chooser" list.
     */
    private static class DialpadChooserAdapter extends BaseAdapter {
        private LayoutInflater mInflater;

        // Simple struct for a single "choice" item.
        static class ChoiceItem {
            String text;
            Bitmap icon;
            int id;

            public ChoiceItem(String s, Bitmap b, int i) {
                text = s;
                icon = b;
                id = i;
            }
        }

        // IDs for the possible "choices":
        static final int DIALPAD_CHOICE_USE_DTMF_DIALPAD = 101;
        static final int DIALPAD_CHOICE_RETURN_TO_CALL = 102;
        static final int DIALPAD_CHOICE_ADD_NEW_CALL = 103;

        private static final int NUM_ITEMS = 3;
        private ChoiceItem mChoiceItems[] = new ChoiceItem[NUM_ITEMS];

        public DialpadChooserAdapter(Context context) {
            // Cache the LayoutInflate to avoid asking for a new one each time.
            mInflater = LayoutInflater.from(context);

            // Initialize the possible choices.
            // TODO: could this be specified entirely in XML?

            // - "Use touch tone keypad"
            mChoiceItems[0] = new ChoiceItem(
                    context.getString(R.string.dialer_useDtmfDialpad),
                    BitmapFactory.decodeResource(context.getResources(),
                                                 R.drawable.ic_dialer_fork_tt_keypad),
                    DIALPAD_CHOICE_USE_DTMF_DIALPAD);

            // - "Return to call in progress"
            mChoiceItems[1] = new ChoiceItem(
                    context.getString(R.string.dialer_returnToInCallScreen),
                    BitmapFactory.decodeResource(context.getResources(),
                                                 R.drawable.ic_dialer_fork_current_call),
                    DIALPAD_CHOICE_RETURN_TO_CALL);

            // - "Add call"
            mChoiceItems[2] = new ChoiceItem(
                    context.getString(R.string.dialer_addAnotherCall),
                    BitmapFactory.decodeResource(context.getResources(),
                                                 R.drawable.ic_dialer_fork_add_call),
                    DIALPAD_CHOICE_ADD_NEW_CALL);
        }

        public int getCount() {
            return NUM_ITEMS;
        }

        /**
         * Return the ChoiceItem for a given position.
         */
        public Object getItem(int position) {
            return mChoiceItems[position];
        }

        /**
         * Return a unique ID for each possible choice.
         */
        public long getItemId(int position) {
            return position;
        }

        /**
         * Make a view for each row.
         */
        public View getView(int position, View convertView, ViewGroup parent) {
            // When convertView is non-null, we can reuse it (there's no need
            // to reinflate it.)
            if (convertView == null) {
                convertView = mInflater.inflate(R.layout.dialpad_chooser_list_item, null);
            }

            TextView text = (TextView) convertView.findViewById(R.id.text);
            text.setText(mChoiceItems[position].text);

            ImageView icon = (ImageView) convertView.findViewById(R.id.icon);
            icon.setImageBitmap(mChoiceItems[position].icon);

            return convertView;
        }
    }

    /**
     * Handle clicks from the dialpad chooser.
     */
    public void onItemClick(AdapterView parent, View v, int position, long id) {
        DialpadChooserAdapter.ChoiceItem item =
                (DialpadChooserAdapter.ChoiceItem) parent.getItemAtPosition(position);
        int itemId = item.id;
        switch (itemId) {
            case DialpadChooserAdapter.DIALPAD_CHOICE_USE_DTMF_DIALPAD:
                // Log.i(TAG, "DIALPAD_CHOICE_USE_DTMF_DIALPAD");
                // Fire off an intent to go back to the in-call UI
                // with the dialpad visible.
                returnToInCallScreen(true);
                break;

            case DialpadChooserAdapter.DIALPAD_CHOICE_RETURN_TO_CALL:
                // Log.i(TAG, "DIALPAD_CHOICE_RETURN_TO_CALL");
                // Fire off an intent to go back to the in-call UI
                // (with the dialpad hidden).
                returnToInCallScreen(false);
                break;

            case DialpadChooserAdapter.DIALPAD_CHOICE_ADD_NEW_CALL:
                // Log.i(TAG, "DIALPAD_CHOICE_ADD_NEW_CALL");
                // Ok, guess the user really did want to be here (in the
                // regular Dialer) after all.  Bring back the normal Dialer UI.
                showDialpadChooser(false);
                break;

            default:
                Log.w(TAG, "onItemClick: unexpected itemId: " + itemId);
                break;
        }
    }

    /**
     * Returns to the in-call UI (where there's presumably a call in
     * progress) in response to the user selecting "use touch tone keypad"
     * or "return to call" from the dialpad chooser.
     */
    private void returnToInCallScreen(boolean showDialpad) {
        try {
            ITelephony phone = ITelephony.Stub.asInterface(ServiceManager.checkService("phone"));
            if (phone != null) phone.showCallScreenWithDialpad(showDialpad);
        } catch (RemoteException e) {
            Log.w(TAG, "phone.showCallScreenWithDialpad() failed", e);
        }

        // Finally, finish() ourselves so that we don't stay on the
        // activity stack.
        // Note that we do this whether or not the showCallScreenWithDialpad()
        // call above had any effect or not!  (That call is a no-op if the
        // phone is idle, which can happen if the current call ends while
        // the dialpad chooser is up.  In this case we can't show the
        // InCallScreen, and there's no point staying here in the Dialer,
        // so we just take the user back where he came from...)
        finish();
    }

    /**
     * @return true if the phone is "in use", meaning that at least one line
     *              is active (ie. off hook or ringing or dialing).
     */
    private boolean phoneIsInUse() {
        boolean phoneInUse = false;
        try {
            ITelephony phone = ITelephony.Stub.asInterface(ServiceManager.checkService("phone"));
            if (phone != null) phoneInUse = !phone.isIdle();
        } catch (RemoteException e) {
            Log.w(TAG, "phone.isIdle() failed", e);
        }
        return phoneInUse;
    }
    
    //Wysie_Soh: add to contacts button / voicemail depending on what is set
    private void initVoicemailButton() {
    	mVoicemailButton = (ImageButton)mVoicemailDialAndDeleteRow.findViewById(R.id.voicemailButton);
    	mVoicemailButton.setOnClickListener(this);
    	
    	if (prefs.getString("vm_button", "0").equals("0")) {
    		mVoicemailButton.setImageResource(R.drawable.ic_add_contacts);    		
    	}
    	else if (prefs.getString("vm_button", "0").equals("1")) { //Wysie_Soh: startsWith changed to equals
    	        mVoicemailButton.setImageResource(R.drawable.ic_dial_action_sms);
    	}
    	else if (prefs.getString("vm_button", "0").equals("2")) {
    		mVoicemailButton.setImageResource(R.drawable.ic_dial_action_voice_mail);
    		
    	        if (hasVoicemail()) {
    	        	mVoicemailButton.setEnabled(true);
    	        } else {
    	        	mVoicemailButton.setEnabled(false);
    	        	mVoicemailButton.setOnClickListener(null);
    	        }
    	}
    }
    
    private int getScreenOrientation() {
    	 return getResources().getConfiguration().orientation;
    }
    
    private boolean hasVoicemail() {
    	boolean hasVoicemail = false;    		
    	
    	try {
    		String num = TelephonyManager.getDefault().getVoiceMailNumber();
    		
    		//Wysie_Soh: Bugfix for 1.52. Important. Caused FCs on many people cause num.equals was called first
    		//resulting in a nullpointerexception. added the exception handler as well, but it's not actually needed
    		//since we now check for num == null first :).
    		if (!(num == null || num.equals(""))) 
    			hasVoicemail = true;
    	} catch (SecurityException se) {
    		// Possibly no READ_PHONE_STATE privilege.
    	} catch (NullPointerException e) {
    		//
    	}
    	
    	return hasVoicemail;
    }
    
    //Wysie_Soh: Method to check if there's any number entered
    private void checkForNumber() {
    		CharSequence digits = mDigits.getText();
    		if ((digits == null || !TextUtils.isGraphic(digits)) && !prefs.getBoolean("dial_disable_num_check", false)) {
    			if (prefs.getString("vm_button", "0").equals("0") || prefs.getString("vm_button", "0").equals("1")) {
            			mVoicemailButton.setEnabled(false);
            			//mDelete.setEnabled(false);
            		}
        	} else {
            		// Put the current digits string into an intent
         		mVoicemailButton.setEnabled(true);
            		//Wysie_Soh: Known bug: mDelete will go into pressed state upon entering any digit after a long-press on mDelete.
            		//mDelete.setEnabled(true);
        	}
    }
    
    private void setDigitsColor() {
        int colorPressed = -16777216;
        int colorFocused = -16777216;
        int colorUnselected = -1;
        
        if (prefs.getBoolean("dial_digit_use_custom_color", false)) {
            try {
                colorFocused = Color.parseColor(prefs.getString("focused_digit_color_custom", "-16777216"));
                colorPressed = Color.parseColor(prefs.getString("pressed_digit_color_custom", "-16777216"));
                colorUnselected = Color.parseColor(prefs.getString("unselected_digit_color_custom", "-1"));
            }
            catch (IllegalArgumentException e) {
                //Do nothing
            }            
        }
        else {
            colorFocused = Integer.parseInt(prefs.getString("focused_digit_color", "-16777216"));
            colorPressed = Integer.parseInt(prefs.getString("pressed_digit_color", "-16777216"));
            colorUnselected = Integer.parseInt(prefs.getString("unselected_digit_color", "-1"));
        }
    
        mDigits.setTextColor(new ColorStateList(
                     new int[][] {
                             new int[] { android.R.attr.state_pressed },
                             new int[] { android.R.attr.state_focused },
                             new int[0]},
                     
                             new int[] { colorPressed, colorFocused, colorUnselected }
                     ));
        mDigits.setCursorVisible(false);
    }

}
