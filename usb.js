// Copyright 2014 The Chromium Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

var exec = require('cordova/exec');
var platformId = require('cordova/platform').id;
var callbackWithError = require('cordova-plugin-chrome-apps-common.errors').callbackWithError;
try {
      var runtime = require('cordova-plugin-chrome-apps-runtime');
} catch(e) {}


exports.getDevices = function(options, callback) {
  cordova.exec(
      function(devices) {  // successCallback
        // TODO(joth): Filter devices according to |options|.
        callback(devices);
      },
      function(msg) {  // errorCallback
        callbackWithError('Get devices failed: ' + msg, callback);
      },
      'ChromeUsb',
      'getDevices',
      [options]
      );
};


exports.openDevice = function(device, callback) {
  cordova.exec(
      function(handle) {  // successCallback
        callback(handle);
      },
      function(msg) {  // errorCallback
        callbackWithError('Open device failed: ' + msg, callback);
      },
      'ChromeUsb',
      'openDevice',
      [device]
      );
};

exports.closeDevice = function(handle, opt_callback) {
  var callback = opt_callback || function() {}
  cordova.exec(
      callback,  // successCallback
      function(msg) {  // errorCallback
        callbackWithError('Close failed: ' + msg, callback);
      },
      'ChromeUsb',
      'closeDevice',
      [{handle:handle.handle}]
      );
}

exports.listInterfaces = function(handle, callback) {
  cordova.exec(
      callback,  // successCallback
      function(msg) {  // errorCallback
        callbackWithError('List interfaces failed: ' + msg, callback, []);
      },
      'ChromeUsb',
      'listInterfaces',
      [{handle:handle.handle}]
      );
};

exports.claimInterface = function(handle, interfaceNumber, callback) {
  if (typeof interfaceNumber != "number") {
    // List interfaces returns an object, the caller must extract the number
    // from it.
    return callbackWithError('interfaceNumber must be a number, not: ' +
        JSON.stringify(interfaceNumber));
  }
  cordova.exec(
      callback, // successCallback
      function(msg) {  // errorCallback
        callbackWithError('Claim failed: ' + msg, callback);
      },
      'ChromeUsb',
      'claimInterface',
      [{ handle: handle.handle,
         interfaceNumber: interfaceNumber}]
      );
};


exports.releaseInterface = function(handle, interfaceNumber, callback) {
  cordova.exec(
      callback,  // successCallback
      function(msg) {  // errorCallback
        callbackWithError('Release interface failed: ' + msg, callback);
      },
      'ChromeUsb',
      'releaseInterface',
      [{handle:handle.handle,
        interfaceNumber:interfaceNumber}]
      );
};


exports.controlTransfer = function(handle, transferInfo, callback) {
  var params = {};
  var ALLOWED_PROPERTIES = [
      'direction', 'recipient', 'requestType', 'request', 'value',
      'index', 'length',
      // Skip 'data' -- sent as positional param 1
  ];

  for (var i = 0; i < ALLOWED_PROPERTIES.length; ++i) {
    var name = ALLOWED_PROPERTIES[i];
    params[name] = transferInfo[name];
  }
  params.handle = handle.handle;
  cordova.exec(
      function(data) {  // successCallback
        callback({resultCode: 0, data:data});
      },
      function(msg) {  // errorCallback
        callbackWithError('Control transfer failed: ' + msg, callback, {resultCode: 1});
      },
      'ChromeUsb',
      'controlTransfer',
      [params, transferInfo['data']]
      );

};


exports.bulkTransfer = function(handle, transferInfo, callback) {
  if (typeof transferInfo.endpoint != "number") {
    // List interfaces returns endpoints an object, the caller must extract the
    // number from it.
    return callbackWithError('endpoint must be a number, not: ' +
        JSON.stringify(transferInfo.endpoint));
  }

  var params = {
    handle: handle.handle,
    direction: transferInfo.direction,
    endpoint: transferInfo.endpoint,
    length: transferInfo.length
  };

  cordova.exec(
      function(data) {  // successCallback
        callback({resultCode: 0, data:data});
      },
      function(msg) {  // errorCallback
        callbackWithError('Bulk transfer failed: ' + msg, callback, {resultCode: 1});
      },
      'ChromeUsb',
      'bulkTransfer',
      [params, transferInfo['data']]
      );
};


exports.requestAccess = function(device, interfaceId, callback) {
  // Deprecated. Always returns true.
  setTimeout(function() {
    callback(true);
  }, 0);
};

