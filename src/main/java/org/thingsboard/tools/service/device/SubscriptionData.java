package org.thingsboard.tools.service.device;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.thingsboard.server.common.data.id.DeviceId;

@Data
@AllArgsConstructor
@NoArgsConstructor
class SubscriptionData {

    private DeviceId deviceId;
    private int subscriptionId;

}
