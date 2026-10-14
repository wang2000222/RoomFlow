package com.roomflow;
/** A real adapter must implement acknowledgements, retries and reconciliation. */
public interface DeviceGateway {
  String open(long roomId, String bookingId);
  String close(long roomId, String bookingId);
}
