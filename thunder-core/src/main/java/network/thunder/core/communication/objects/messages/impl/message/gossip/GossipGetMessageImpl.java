package network.thunder.core.communication.objects.messages.impl.message.gossip;

import com.google.common.base.Preconditions;
import network.thunder.core.communication.objects.messages.interfaces.message.gossip.types.GossipGetMessage;

import java.util.ArrayList;

/**
 * Created by matsjerratsch on 30/11/2015.
 */
public class GossipGetMessageImpl implements GossipGetMessage {
    ArrayList<byte[]> inventoryList;

    @Override
    public ArrayList<byte[]> getInventoryList () {
        return inventoryList;
    }

    @Override
    public void verify () {
        Preconditions.checkNotNull(inventoryList);
    }
}