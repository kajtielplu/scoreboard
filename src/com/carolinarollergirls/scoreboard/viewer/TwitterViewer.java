package com.carolinarollergirls.scoreboard.viewer;
/**
 * Copyright (C) 2008-2012 Mr Temper <MrTemper@CarolinaRollergirls.com>
 *
 * This file is part of the Carolina Rollergirls (CRG) ScoreBoard.
 * The CRG ScoreBoard is licensed under either the GNU General Public
 * License version 3 (or later), or the Apache License 2.0, at your option.
 * See the file COPYING for details.
 */

import java.util.*;
import java.util.concurrent.*;
import java.io.*;

import twitter4j.*;
import twitter4j.conf.*;
import twitter4j.auth.*;

import com.carolinarollergirls.scoreboard.*;
import com.carolinarollergirls.scoreboard.event.*;

public class TwitterViewer implements ScoreBoardViewer
{
  public TwitterViewer() {
    reset();
  }

  public void setScoreBoard(ScoreBoard sB) {
    scoreBoard = sB;
    formatSpecifier = new FormatSpecifierViewer(scoreBoard);
  }

  public void addConditionalTweet(String conditionFormat, String tweet) throws TooManyListenersException {
    synchronized (conditionalListeners) {
      String key = getConditionalListenerKey(conditionFormat, tweet);
      if (conditionalListeners.containsKey(key))
        throw new TooManyListenersException("Conditional tweet with given parameters already exists");
      ScoreBoardListener tweetListener = new TweetScoreBoardListener(tweet);
      ScoreBoardListener conditionalListener =
        new FormatSpecifierScoreBoardListener(formatSpecifier, conditionFormat, tweetListener);
      conditionalListeners.put(key, conditionalListener);
      scoreBoard.addScoreBoardListener(conditionalListener);
    }
  }
  public void removeConditionalTweet(String conditionFormat, String tweet) {
    synchronized (conditionalListeners) {
      ScoreBoardListener listener = conditionalListeners.remove(getConditionalListenerKey(conditionFormat, tweet));
      if (null != listener)
        scoreBoard.removeScoreBoardListener(listener);
    }
  }
  protected String getConditionalListenerKey(String format, String tweet) {
    return "CONDITIONFORMAT:"+format+"TWEET:"+tweet;
  }

  public String getAuthorizationURL(String callbackURL) throws TwitterException,IllegalStateException {
    synchronized (twitterLock) {
      if (null != requestToken)
        reset();
      requestToken = twitter.getOAuthRequestToken(callbackURL);
      return requestToken.getAuthorizationURL();
    }
  }

  public void setOAuthVerifier(String verifier) throws TwitterException {
    synchronized (twitterLock) {
      /* should throw exception if no requestToken or already logged in */
      AccessToken accessToken = twitter.getOAuthAccessToken(requestToken, verifier);
      userId = twitter.getId();
      twitterStream = twitterStreamFactory.getInstance(accessToken);
      twitterStream.addListener(userStreamListener);
      twitterStream.user();
      requestToken = null;
      loggedIn = true;
    }
  }

  public void addTweetListener(TweetListener listener) {
    synchronized (twitterLock) {
      if (!tweetListeners.containsKey(listener))
        tweetListeners.put(listener, Executors.newSingleThreadExecutor());
    }
  }
  public void removeTweetListener(TweetListener listener) {
    synchronized (twitterLock) {
      if (tweetListeners.containsKey(listener))
        tweetListeners.remove(listener).shutdown();
    }
  }
  protected void notifyTweetListeners(final Status status) {
    synchronized (twitterLock) {
      Iterator<TweetListener> listeners = tweetListeners.keySet().iterator();
      while (listeners.hasNext()) {
        final TweetListener listener = listeners.next();
        Runnable r = new Runnable() { public void run() { listener.tweet(status.getText()); } };
        tweetListeners.get(listener).submit(r);
      }
    }
  }

  public String getScreenName() throws TwitterException {
    synchronized (twitterLock) {
      return twitter.getScreenName();
    }
  }

  public void logout() {
    synchronized (twitterLock) {
      reset();
    }
  }

  protected void reset() {
    if (null != twitter)
      twitter.shutdown();
    twitter = twitterFactory.getInstance();
    userId = 0;
    requestToken = null;
    if (null != twitterStream)
      twitterStream.cleanUp();
    twitterStream = null;
    loggedIn = false;
  }

  public void tweet(String tweet) throws TwitterException {
    synchronized (twitterLock) {
      /* will throw exception if not logged in */
      twitter.updateStatus(formatSpecifier.parse(tweet));
    }
  }

  public boolean isLoggedIn() { return loggedIn; }

  private ConfigurationBuilder getConfigurationBuilder() {
    return new ConfigurationBuilder()
      .setDebugEnabled(false)
      .setUserStreamRepliesAllEnabled(false)
      .setOAuthConsumerKey("LcSklLv7gic519YE5ylK1g")
      .setOAuthConsumerSecret("BXjvuTrbl6rTIgybxqCTIfZS7obv2OdUYiM1n8V3Q");
  }

  protected TwitterFactory twitterFactory = new TwitterFactory(getConfigurationBuilder().build());
  protected TwitterStreamFactory twitterStreamFactory = new TwitterStreamFactory(getConfigurationBuilder().build());

  protected ScoreBoard scoreBoard;
  protected FormatSpecifierViewer formatSpecifier;

  protected Map<String,ScoreBoardListener> conditionalListeners = new HashMap<String,ScoreBoardListener>();

  protected Twitter twitter;
  protected Object twitterLock = new Object();
  protected long userId;

  protected boolean loggedIn = false;

  protected RequestToken requestToken = null;
  protected TwitterStream twitterStream = null;

  protected Map<TweetListener,ExecutorService> tweetListeners = new HashMap<TweetListener,ExecutorService>();

  protected UserStreamListener userStreamListener = new UserStreamAdapter() {
      public void onStatus(Status status) {
        if (status.getUser().getId() == userId)
          notifyTweetListeners(status);
      }
    };

  protected class TweetScoreBoardListener implements ScoreBoardListener
  {
    public TweetScoreBoardListener(String t) { tweet = t; }
    public void scoreBoardChange(ScoreBoardEvent e) {
      try {
        if (loggedIn)
          tweet(tweet);
      } catch ( TwitterException tE ) {
        ScoreBoardManager.printMessage("Error trying to tweet : "+tE.getMessage());
      }
    }
    protected String tweet;
  }

  public static interface TweetListener
  {
    public void tweet(String tweet);
  }
}