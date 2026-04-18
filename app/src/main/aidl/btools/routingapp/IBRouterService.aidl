package btools.routingapp;

// Mirror of the interface shipped by the BRouter app
// (github.com/abrensch/brouter). AIDL compatibility only — we bind to
// the installed BRouter package and call getTrackFromParams, which
// does the real bike-aware routing locally on device.
interface IBRouterService {
    String getTrackFromParams(in Bundle params);
}
