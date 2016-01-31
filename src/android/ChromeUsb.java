// Copyright 2014 The Chromium Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package org.chromium;

import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.util.Arrays;
import java.util.HashMap;

import org.apache.cordova.CallbackContext;
import org.apache.cordova.CordovaArgs;
import org.apache.cordova.CordovaPlugin;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import android.annotation.TargetApi;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.hardware.usb.UsbRequest;
import android.os.Build;
import android.util.Base64;
import android.util.Log;

@TargetApi(Build.VERSION_CODES.JELLY_BEAN)
public class ChromeUsb extends CordovaPlugin {
    private static final String TAG = "ChromeUsb";
    private static final String ACTION_USB_PERMISSION = TAG + ".USB_PERMISSION";

    // Index of the 'params' object in CordovaArgs array passed in each action.
    private static final int ARG_INDEX_PARAMS = 0;
    // Index of the 'data' ArrayBuffer in CordovaArgs array passed in each action (where relevant).
    private static final int ARG_INDEX_DATA_ARRAYBUFFER = 1;

    // An endpoint address is constructed from the interface index left-shifted this many bits,
    // or-ed with the endpoint index.
    private static final int ENDPOINT_IF_SHIFT = 16;

    private UsbManager mUsbManager;
    private PendingIntent mPermissionIntent;
    private BroadcastReceiver mUsbReceiver;

    // Encapsulates the Android UsbDevice and UsbDeviceConnection classes, and provides wrappers
    // around the UsbInterface and UsbEndpoint methods to allow for mocking.
    private static abstract class ConnectedDevice {
        abstract int getInterfaceCount();
        abstract int getEndpointCount(int interfaceNumber);
        abstract void describeInterface(int interfaceNumber, JSONObject result)
                throws JSONException;
        abstract void describeEndpoint(int interfaceNumber, int endpointNumber, JSONObject result)
                throws JSONException;
        abstract boolean claimInterface(int interfaceNumber);
        abstract boolean releaseInterface(int interfaceNumber);
        abstract int controlTransfer(int requestType, int request, int value, int index,
                byte[] transferBuffer, byte[] receiveBuffer, int timeout);
        abstract int bulkTransfer(int interfaceNumber, int endpointNumber, int direction,
                byte[] buffer, int timeout) throws UsbError;
        abstract int interruptTransfer(int interfaceNumber, int endpointNumber, int direction,
                                       byte[] buffer, int timeout) throws UsbError;
        abstract void close();
    };

    // Maps connection handles to the corresponding device & connection objects.
    private HashMap<Integer, ConnectedDevice> mConnections =
            new HashMap<Integer, ConnectedDevice>();
    private static int mNextConnectionId = 1;

    @Override
    public void onDestroy() {
        Log.d(TAG, "onDestroy");
        for (ConnectedDevice d : mConnections.values()) {
            d.close();
        }
        mConnections.clear();
    }

    private CallbackContext openCallbackContext;

    /**
     * Overridden execute method
     * @param action the string representation of the action to execute
     * @param args
     * @param callbackContext the cordova {@link CallbackContext}
     * @return true if the action exists, false otherwise
     * @throws JSONException if the args parsing fails
     */
    @Override
    public boolean execute(String action, final CordovaArgs args, final CallbackContext callbackContext)
            throws JSONException {
        final JSONObject params = args.getJSONObject(ARG_INDEX_PARAMS);
        final CordovaArgs finalArgs = args;
        Log.d(TAG, "Action: " + action + " params: " + params);

        if (mUsbManager == null) {
            mUsbManager = (UsbManager) webView.getContext().getSystemService(Context.USB_SERVICE);
            mPermissionIntent = PendingIntent.getBroadcast(webView.getContext(), 0, new Intent(ACTION_USB_PERMISSION), 0);
        }

        // TODO: Process commands asynchronously on a worker pool thread.
        try {
            if ("getDevices".equals(action)) {
                getDevices(args, params, callbackContext);
                return true;
            } else if ("openDevice".equals(action)) {
                this.openCallbackContext = callbackContext;
                cordova.getThreadPool().execute(new Runnable() {
                    public void run() {
                        try {
                            openDevice(args, params, callbackContext);
                        } catch (Exception e) {
                            callbackContext.error(e.getMessage());
                        }
                    }
                });
                return true;
            } else if ("closeDevice".equals(action)) {
                closeDevice(args, params, callbackContext);
                return true;
            } else if ("listInterfaces".equals(action)) {
                listInterfaces(args, params, callbackContext);
                return true;
            } else if ("claimInterface".equals(action)) {
                claimInterface(args, params, callbackContext);
                return true;
            } else if ("releaseInterface".equals(action)) {
                releaseInterface(args, params, callbackContext);
                return true;
            } else if ("controlTransfer".equals(action)) {
                cordova.getThreadPool().execute(new Runnable() {
                    public void run() {
                        try {
                            controlTransfer(finalArgs, params, callbackContext);
                        } catch (Exception e) {
                            callbackContext.error(e.getMessage());
                        }
                    }
                });
                return true;
            } else if ("bulkTransfer".equals(action)) {
                cordova.getThreadPool().execute(new Runnable() {
                    public void run() {
                        try {
                            bulkTransfer(finalArgs, params, callbackContext);
                        } catch (Exception e) {
                            callbackContext.error(e.getMessage());
                        }
                    }
                });
                return true;
            } else if ("interruptTransfer".equals(action)) {
                cordova.getThreadPool().execute(new Runnable() {
                    public void run() {
                        try {
                            interruptTransfer(finalArgs, params, callbackContext);
                        } catch (Exception e) {
                            callbackContext.error(e.getMessage());
                        }
                    }
                });
                return true;
            }
        } catch (UsbError e) {
            callbackContext.error(e.getMessage());
            return true;
        } catch (JSONException e) {
            callbackContext.error(e.getMessage());
            return true;
        }
        return false;
    }
    private boolean filterDevice(UsbDevice device, JSONArray filters) throws JSONException {
        if (filters == null) {
            return true;
        }
        Log.d(TAG, "filtering " + filters);
        for (int filterIdx = 0; filterIdx < filters.length(); filterIdx++) {
            JSONObject filter = filters.getJSONObject(filterIdx);
            int vendorId = filter.optInt("vendorId", -1);
            if (vendorId != -1) {
                if (device.getVendorId() != vendorId) {
                    continue;
                }
            }
            int productId = filter.optInt("productId", -1);
            if (productId != -1) {
                if (device.getProductId() != productId) {
                    continue;
                }
            }
            int interfaceClass = filter.optInt("interfaceClass", -1);
            int interfaceSubclass = filter.optInt("interfaceSubclass", -1);
            int interfaceProtocol = filter.optInt("interfaceProtocol", -1);
            if (interfaceClass == -1 && interfaceSubclass == -1 && interfaceProtocol == -1) {
                return true;
            }
            int interfaceCount = device.getInterfaceCount();
            for (int interfaceIdx = 0; interfaceIdx < interfaceCount; interfaceIdx++) {
                UsbInterface usbInterface = device.getInterface(interfaceIdx);
                if (interfaceClass != -1) {
                    if (interfaceClass != usbInterface.getInterfaceClass()) {
                        continue;
                    }
                }
                if (interfaceSubclass != -1) {
                    if (interfaceSubclass != usbInterface.getInterfaceSubclass()) {
                        continue;
                    }
                }
                if (interfaceProtocol != -1) {
                    if (interfaceProtocol != usbInterface.getInterfaceProtocol()) {
                        continue;
                    }
                }
                return true;
            }
        }
        return false;
    }
    private void getDevices(CordovaArgs args, JSONObject params,
            final CallbackContext callbackContext) throws JSONException, UsbError {
        HashMap<String, UsbDevice> devices = mUsbManager.getDeviceList();
        JSONArray filters = params.optJSONArray("filters");
        JSONArray result = new JSONArray();
        for (UsbDevice device: devices.values()) {
            if (filterDevice(device, filters)) {
                addDeviceToArray(result, device.getDeviceId(), device.getVendorId(),
                        device.getProductId());
            }
        }
        if (params.optBoolean("appendFakeDevice", false)) {
            addDeviceToArray(result, FakeDevice.ID, FakeDevice.VID, FakeDevice.PID);
        }
        callbackContext.success(result);
    }
    private static void addDeviceToArray(JSONArray result, int deviceId, int vendorId,
            int productId) throws JSONException {
        JSONObject jsonDev = new JSONObject();
        jsonDev.put("device", deviceId);
        jsonDev.put("vendorId", vendorId);
        jsonDev.put("productId", productId);
        result.put(jsonDev);
    }
    private void openDevice(CordovaArgs args, JSONObject params,
            final CallbackContext callbackContext) throws JSONException, UsbError {
        // First recover the device object from Id.
        int devId = params.getInt("device");
        ConnectedDevice dev = null;
        int vid = -1, pid = -1;
        {
            HashMap<String, UsbDevice> devices = mUsbManager.getDeviceList();
            UsbDevice usbDev = null;
            for (UsbDevice d : devices.values()) {
                if (d.getDeviceId() == devId) {
                    usbDev = d;
                    break;
                }
            }
            if (usbDev != null) {
                if(mUsbReceiver == null) {
                    mUsbReceiver = new BroadcastReceiver() {
                        public void onReceive(Context context, Intent intent) {
                            String action = intent.getAction();
                            if (ACTION_USB_PERMISSION.equals(action)) {
                                synchronized (this) {
                                    UsbDevice device = (UsbDevice) intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);

                                    if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                                        if (device != null) {
                                            UsbDeviceConnection usbConn = mUsbManager.openDevice(device);
                                            if (usbConn == null) {
                                                throw new UsbError("UsbManager.openDevice returned null opening " + device);
                                            }
                                            ConnectedDevice dev = new RealDevice(device, usbConn);
                                            int vid = device.getVendorId();
                                            int pid = device.getProductId();

                                            if (dev == null || vid < 0 || pid < 0) {
                                                throw new UsbError("Unknown device ID: " + device);
                                            }
                                            int handle = mNextConnectionId++;
                                            mConnections.put(handle, dev);
                                            JSONObject jsonHandle = new JSONObject();
                                            try {
                                                jsonHandle.put("handle", handle);
                                                jsonHandle.put("vendorId", vid);
                                                jsonHandle.put("productId", pid);
                                            } catch (JSONException e) {
                                                e.printStackTrace();
                                            }
                                            if (null != ChromeUsb.this.openCallbackContext) {
                                                ChromeUsb.this.openCallbackContext.success(jsonHandle);
                                                ChromeUsb.this.openCallbackContext = null;
                                            } else {
                                                // at least fire to someone...
                                                callbackContext.success(jsonHandle);
                                            }
                                        }
                                    } else {
                                        Log.d(TAG, "permission denied for device " + device);
                                    }
                                }
                            }
                        }
                    };

                    IntentFilter filter = new IntentFilter(ACTION_USB_PERMISSION);
                    webView.getContext().registerReceiver(mUsbReceiver, filter);
                }

                mUsbManager.requestPermission(usbDev, mPermissionIntent);
            } else if (devId == FakeDevice.ID) {
                dev = new FakeDevice();
                vid = FakeDevice.VID;
                pid = FakeDevice.PID;

                if (dev == null || vid < 0 || pid < 0) {
                    throw new UsbError("Unknown device ID: " + devId);
                }
                int handle = mNextConnectionId++;
                mConnections.put(handle, dev);
                JSONObject jsonHandle = new JSONObject();
                jsonHandle.put("handle", handle);
                jsonHandle.put("vendorId", vid);
                jsonHandle.put("productId", pid);
                callbackContext.success(jsonHandle);
            }
        }
    }
    private void closeDevice(CordovaArgs args, JSONObject params,
            final CallbackContext callbackContext) throws JSONException, UsbError {
        int handle = params.getInt("handle");
        ConnectedDevice d = mConnections.remove(handle);
        if (d != null) {
            d.close();
        }
        callbackContext.success();
    }
    private void listInterfaces(CordovaArgs args, JSONObject params,
            final CallbackContext callbackContext) throws JSONException, UsbError {
        ConnectedDevice dev = getDevice(params);
        JSONArray jsonInterfaces = new JSONArray();
        int interfaceCount = dev.getInterfaceCount();
        for (int i = 0; i < interfaceCount; i++) {
            JSONArray jsonEndpoints = new JSONArray();
            int endpointCount = dev.getEndpointCount(i);
            for (int j = 0; j < endpointCount; j++) {
                JSONObject jsonEp = new JSONObject();
                dev.describeEndpoint(i, j, jsonEp);
                jsonEp.put("address", i << ENDPOINT_IF_SHIFT | j);
                if (!jsonEp.getString("type").startsWith("i")) {
                    // Only interrupt and isochronous endpoints have pollingInterval.
                    jsonEp.remove("pollingInterval");
                }
                jsonEp.put("extra_data", new JSONObject());
                jsonEndpoints.put(jsonEp);
            }
            JSONObject jsonIf = new JSONObject();
            dev.describeInterface(i, jsonIf);
            jsonIf.put("interfaceNumber", i);
            jsonIf.put("endpoints", jsonEndpoints);
            jsonInterfaces.put(jsonIf);
        }
        callbackContext.success(jsonInterfaces);
    }
    private void claimInterface(CordovaArgs args, JSONObject params,
            final CallbackContext callbackContext) throws JSONException, UsbError {
        ConnectedDevice dev = getDevice(params);
        int interfaceNumber = getInterfaceNumber(params, dev);
        if (!dev.claimInterface(interfaceNumber)) {
            throw new UsbError("claimInterface returned false for i/f: " + interfaceNumber);
        }
        callbackContext.success();
    }
    private void releaseInterface(CordovaArgs args, JSONObject params,
            final CallbackContext callbackContext) throws JSONException, UsbError {
        ConnectedDevice dev = getDevice(params);
        int interfaceNumber = getInterfaceNumber(params, dev);
        if (!dev.releaseInterface(interfaceNumber)) {
            throw new UsbError("releaseInterface returned false for i/f: " + interfaceNumber);
        }
        callbackContext.success();
    }
    private void controlTransfer(CordovaArgs args, JSONObject params,
            final CallbackContext callbackContext) throws JSONException, UsbError {
        ConnectedDevice dev = getDevice(params);

        int direction = directionFromName(params.getString("direction"));
        int requestType = controlRequestTypeFromName(params.getString("requestType"));
        int recipient = recipientFromName(params.getString("recipient"));

        byte[] transferBuffer = getByteBufferForTransfer(args, params, UsbConstants.USB_DIR_OUT);
        byte[] receiveBuffer = getByteBufferForTransfer(args, params, UsbConstants.USB_DIR_IN);

        int ret = dev.controlTransfer(
                direction | requestType | recipient,
                params.getInt("request"),
                params.getInt("value"),
                params.getInt("index"),
                transferBuffer,
                receiveBuffer,
                params.getInt("timeout"));
        if (ret < 0) {
            throw new UsbError("Control transfer returned " + ret);
        }

        /* control transfer is bidirectional, buffer should alway be passed */
        callbackContext.success(Arrays.copyOf(receiveBuffer, receiveBuffer.length));
    }
    private void bulkTransfer(CordovaArgs args, JSONObject params,
            final CallbackContext callbackContext) throws JSONException, UsbError {
        ConnectedDevice dev = getDevice(params);
        int endpointAddress = params.getInt("endpoint");
        int interfaceNumber = endpointAddress >> ENDPOINT_IF_SHIFT;
        int endpointNumber = endpointAddress & ((1 << ENDPOINT_IF_SHIFT) - 1);
        if (interfaceNumber >= dev.getInterfaceCount() ||
                endpointNumber >= dev.getEndpointCount(interfaceNumber)) {
            throw new UsbError("Enpoint not found: " + endpointAddress);
        }
        int direction = directionFromName(params.getString("direction"));
        byte[] buffer = getByteBufferForTransfer(args, params, direction);

        int ret = dev.bulkTransfer(interfaceNumber, endpointNumber, direction, buffer,
                params.getInt("timeout"));
        if (ret < 0) {
            throw new UsbError("Bulk transfer returned " + ret);
        }
        if (direction == UsbConstants.USB_DIR_IN) {
            callbackContext.success(Arrays.copyOf(buffer, ret));
        } else {
            callbackContext.success();
        }
    }
    private void interruptTransfer(CordovaArgs args, JSONObject params,
                                   final CallbackContext callbackContext) throws JSONException, UsbError {
        ConnectedDevice dev = getDevice(params);
        int endpointAddress = params.getInt("endpoint");
        int interfaceNumber = endpointAddress >> ENDPOINT_IF_SHIFT;
        int endpointNumber = endpointAddress & ((1 << ENDPOINT_IF_SHIFT) - 1);
        if (interfaceNumber >= dev.getInterfaceCount() ||
                endpointNumber >= dev.getEndpointCount(interfaceNumber)) {
            throw new UsbError("Enpoint not found: " + endpointAddress);
        }

        int direction = directionFromName(params.getString("direction"));
        byte[] buffer = getByteBufferForTransfer(args, params, direction);

        int ret = dev.interruptTransfer(interfaceNumber, endpointNumber, direction, buffer,
                params.getInt("timeout"));
        if (ret < 0) {
            throw new UsbError("Interrupt transfer returned " + ret);
        }
        if (direction == UsbConstants.USB_DIR_IN) {
            callbackContext.success(Arrays.copyOf(buffer, ret));
        } else {
            callbackContext.success();
        }
    }
    private ConnectedDevice getDevice(JSONObject params) throws JSONException, UsbError {
        int handle = params.getInt("handle");
        ConnectedDevice d = mConnections.get(handle);
        if (d == null) {
            throw new UsbError("Unknown connection handle: " + handle);
        }
        return d;
    }
    private int getInterfaceNumber(JSONObject params, ConnectedDevice device)
            throws JSONException, UsbError {
        int interfaceNumber = params.getInt("interfaceNumber");
        if (interfaceNumber >= device.getInterfaceCount()) {
            throw new UsbError("interface number " + interfaceNumber + " out of range 0.."
                    + device.getInterfaceCount());
        }
        return interfaceNumber;
    }

    // Internal exception type used to simplify the action dispatcher error paths.
    private static class UsbError extends RuntimeException {
        UsbError(String msg) {
            super(msg);
        }
    }

    // Concrete subclass of ConnectedDevice that routes calls through to the real Android APIs.
    // The implementation of this class is by design very minimalist: if the methods are kept free
    // of logic/control statements as the test strategy (see FakeDevice) does not cover this class.
    private static class RealDevice extends ConnectedDevice {
        RealDevice(UsbDevice device, UsbDeviceConnection connection) {
            mDevice = device;
            mConnection = connection;
        }

        private final UsbDevice mDevice;
        private final UsbDeviceConnection mConnection;

        int getInterfaceCount() {
            return mDevice.getInterfaceCount();
        }
        int getEndpointCount(int interfaceNumber) {
            return mDevice.getInterface(interfaceNumber).getEndpointCount();
        }
        void describeInterface(int interfaceNumber, JSONObject res) throws JSONException {
            UsbInterface i = mDevice.getInterface(interfaceNumber);
            res.put("alternateSetting", 0);  // TODO: In LOLLIPOP use i.getAlternateSetting());
            res.put("interfaceClass", i.getInterfaceClass());
            res.put("interfaceProtocol", i.getInterfaceProtocol());
            res.put("interfaceSubclass", i.getInterfaceSubclass());
            byte[] rawDescriptors = mConnection.getRawDescriptors();
            byte[] extraDescriptor = null;
            int idx = 0;
            while (idx < rawDescriptors.length) {
                byte length = rawDescriptors[idx];
                byte descriptorType = rawDescriptors[idx + 1];
                if (descriptorType == 0x04) {
                    // interface descriptor
                    int descriptorInterfaceNumber = rawDescriptors[idx + 2];
                    if (interfaceNumber == descriptorInterfaceNumber) {
                        Log.d(TAG, "Interface descriptor found: " + interfaceNumber);
                        idx += length;
                        if (idx < rawDescriptors.length) {
                            length = rawDescriptors[idx];
                            descriptorType = rawDescriptors[idx + 1];
                            if (descriptorType == 0x01 || descriptorType == 0x02 || descriptorType == 0x04 || descriptorType == 0x05) {
                                break;
                            }
                            extraDescriptor = new byte[length];
                            System.arraycopy(rawDescriptors, idx, extraDescriptor, 0, length);
                            break;
                        }
                        break;
                    }
                }
                idx += length;
            }
            if (null == extraDescriptor) {
                extraDescriptor = new byte[0];
            }
            res.put("extra_data", Base64.encodeToString(extraDescriptor, Base64.NO_WRAP));
        }
        void describeEndpoint(int interfaceNumber, int endpointNumber, JSONObject res)
                throws JSONException {
            UsbEndpoint ep = mDevice.getInterface(interfaceNumber).getEndpoint(endpointNumber);
            res.put("direction", directionName(ep.getDirection()));
            res.put("maximumPacketSize", ep.getMaxPacketSize());
            res.put("pollingInterval", ep.getInterval());
            res.put("type", endpointTypeName(ep.getType()));
        }
        boolean claimInterface(int interfaceNumber) {
            return mConnection.claimInterface(mDevice.getInterface(interfaceNumber), true);
        }
        boolean releaseInterface(int interfaceNumber) {
            return mConnection.releaseInterface(mDevice.getInterface(interfaceNumber));
        }
        private static String getResultString(byte[] in) {
            String ret = "";

            for(int n = 0; n < in.length; n++) {
                String s = "0" + Integer.toHexString(in[n]);
                ret += s.substring(s.length() - 2) + " ";
            }

            return ret;
        }
        int controlTransfer(int requestType, int request, int value, int index,
                            byte[] transferBuffer, byte[] receiveBuffer, int timeout) {
            UsbEndpoint ep = mDevice.getInterface(0).getEndpoint(0);
            int result = -1;

            if(ep.getType() == UsbConstants.USB_ENDPOINT_XFER_INT &&
                    ep.getDirection() == UsbConstants.USB_DIR_IN) {
                ByteBuffer bb = ByteBuffer.wrap(receiveBuffer);
                UsbRequest ur = new UsbRequest();

                ur.initialize(mConnection, ep);

                ur.queue(bb, receiveBuffer.length);

                result = mConnection.controlTransfer(requestType, request, value, index,
                        transferBuffer, transferBuffer.length, timeout);

                if(result >= 0) {
                    if (mConnection.requestWait() != ur) {
                        Log.e(TAG, "[controlTransfer] requestWait failed");

                        return -1;
                    }
                } else {
                    Log.e(TAG, "[controlTransfer] Transfer failed");
                }
            } else {
                result = mConnection.controlTransfer(requestType, request, value, index,
                        transferBuffer, transferBuffer.length, timeout);

                receiveBuffer = transferBuffer.clone();
            }

            return result;
        }
        int bulkTransfer(int interfaceNumber, int endpointNumber, int direction,
                         byte[] buffer, int timeout)
                throws UsbError {
            UsbEndpoint ep = mDevice.getInterface(interfaceNumber).getEndpoint(endpointNumber);
            if (ep.getDirection() != direction) {
                throw new UsbError("Endpoint has direction: " + directionName(ep.getDirection()));
            }
            return mConnection.bulkTransfer(ep, buffer, buffer.length, timeout);
        }
        int interruptTransfer(int interfaceNumber, int endpointNumber, int direction,
                              byte[] buffer, int timeout)
                throws UsbError {
            UsbEndpoint ep = mDevice.getInterface(interfaceNumber).getEndpoint(endpointNumber);
            if (ep.getDirection() != direction) {
                throw new UsbError("Endpoint has direction: " + directionName(ep.getDirection()));
            }
            return mConnection.bulkTransfer(ep, buffer, buffer.length, timeout);
        }
        void close() {
            mConnection.close();
        }
    };

    // Fake device, used in test code.
    private static class FakeDevice extends ConnectedDevice {
        static final int ID = -1000000;
        static final int VID = 0x18d1;  // Google VID.
        static final int PID = 0x2001;  // Reserved for non-production uses.

        private byte[] echoBytes = null;

        int getInterfaceCount() {
            return 1;
        }
        int getEndpointCount(int interfaceNumber) {
            return 2;
        }
        void describeInterface(int interfaceNumber, JSONObject res) throws JSONException {
            res.put("alternateSetting", 0);
            res.put("interfaceClass", 255);
            res.put("interfaceProtocol", 255);
            res.put("interfaceSubclass", 255);
            res.put("extra_data", new JSONObject());
        }
        void describeEndpoint(int interfaceNumber, int endpointNumber, JSONObject res)
                throws JSONException {
            res.put("direction", directionName(endpointNumber == 0 ?
                        UsbConstants.USB_DIR_IN : UsbConstants.USB_DIR_OUT));
            res.put("maximumPacketSize", 64);
            res.put("pollingInterval", 0);
            res.put("type", endpointTypeName(UsbConstants.USB_ENDPOINT_XFER_BULK));
        }
        boolean claimInterface(int interfaceNumber) {
            return true;
        }
        boolean releaseInterface(int interfaceNumber) {
            return true;
        }
        int controlTransfer(int requestType, int request, int value, int index,
                            byte[] transferBuffer, byte[] receiveBuffer, int timeout) {
            if ((requestType & UsbConstants.USB_ENDPOINT_DIR_MASK) == UsbConstants.USB_DIR_IN) {
                // For an 'IN' transfer, reflect params into the response data.
                receiveBuffer[0] = (byte)request;
                receiveBuffer[1] = (byte)value;
                receiveBuffer[2] = (byte)index;
                return 3;
            }
            return transferBuffer.length;
        }
        int bulkTransfer(int interfaceNumber, int endpointNumber, int direction,
                         byte[] buffer, int timeout)
                throws UsbError {
            if (direction == UsbConstants.USB_DIR_OUT) {
                echoBytes = buffer;
                return echoBytes.length;
            }
            // IN transfer.
            if (echoBytes == null) {
                return 0;
            }
            int len = Math.min(echoBytes.length, buffer.length);
            System.arraycopy(echoBytes, 0, buffer, 0, len);
            echoBytes = null;
            return len;
        }
        int interruptTransfer(int interfaceNumber, int endpointNumber, int direction,
                              byte[] buffer, int timeout)
                throws UsbError {
            if (direction == UsbConstants.USB_DIR_OUT) {
                echoBytes = buffer;
                return echoBytes.length;
            }
            // IN transfer.
            if (echoBytes == null) {
                return 0;
            }
            int len = Math.min(echoBytes.length, buffer.length);
            System.arraycopy(echoBytes, 0, buffer, 0, len);
            echoBytes = null;
            return len;
        }
        void close() {
        }
    };

    static String directionName(int direction) {
        switch (direction) {
            case UsbConstants.USB_DIR_IN: return "in";
            case UsbConstants.USB_DIR_OUT: return "out";
            default: return "ERR:" + direction;
        }
    }

    static String endpointTypeName(int type) {
        switch (type) {
            case UsbConstants.USB_ENDPOINT_XFER_BULK: return "bulk";
            case UsbConstants.USB_ENDPOINT_XFER_CONTROL: return "control";
            case UsbConstants.USB_ENDPOINT_XFER_INT: return "interrupt";
            case UsbConstants.USB_ENDPOINT_XFER_ISOC: return "isochronous";
            default: return "ERR:" + type;
        }
    }

    private static int controlRequestTypeFromName(String requestType) throws UsbError{
        requestType = requestType.toLowerCase();
        if ("standard".equals(requestType)) {
            return UsbConstants.USB_TYPE_STANDARD;  /* 0x00 */
        } else if ("class".equals(requestType)) {
            return UsbConstants.USB_TYPE_CLASS;     /* 0x20 */
        } else if ("vendor".equals(requestType)) {
            return UsbConstants.USB_TYPE_VENDOR;    /* 0x40 */
        } else if ("reserved".equals(requestType)) {
            return UsbConstants.USB_TYPE_RESERVED;  /* 0x60 */
        } else {
            throw new UsbError("Unknown transfer requestType: " + requestType);
        }
    }

    private static int recipientFromName(String recipient) throws UsbError {
        /* recipient value from pyUSB */
        recipient = recipient.toLowerCase();

        if("device".equals(recipient)) {
            return 0;
        } else if("interface".equals(recipient)) {
            return 1;
        } else if("endpoint".equals(recipient)) {
            return 2;
        } else if("other".equals(recipient)) {
            return 3;
        } else {
            throw new UsbError("Unknown recipient: " + recipient);
        }
    }

    private static int directionFromName(String direction) throws UsbError {
        direction = direction.toLowerCase();
        if ("out".equals(direction)) {
            return UsbConstants.USB_DIR_OUT; /* 0x00 */
        } else if ("in".equals(direction)) {
            return UsbConstants.USB_DIR_IN; /* 0x80 */
        } else {
            throw new UsbError("Unknown transfer direction: " + direction);
        }
    }

    private static byte[] getByteBufferForTransfer(CordovaArgs args, JSONObject params,
            int direction) throws JSONException {
        if (direction == UsbConstants.USB_DIR_OUT) {
            // OUT transfer requires data positional argument.
            return args.getArrayBuffer(ARG_INDEX_DATA_ARRAYBUFFER);
        } else {
            // IN transfer requires client to pass the length to receive.
            return new byte[params.optInt("length")];
        }
    }
}

