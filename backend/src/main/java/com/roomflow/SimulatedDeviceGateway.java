package com.roomflow;

import org.springframework.stereotype.Component;

@Component
public class SimulatedDeviceGateway implements DeviceGateway {
  public String open(long roomId, String bookingId) {
    return "SIMULATED_OPEN:" + bookingId;
  }

  public String close(long roomId, String bookingId) {
    return "SIMULATED_CLOSE:" + bookingId;
  }
}
