package ae2.container.guisync;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.invoke.VarHandle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class SynchronizedFieldTest {
    @BeforeAll
    static void registerCustomField() {
        SynchronizedField.register(CustomValue.class, CustomValueField::new);
    }

    @Test
    void synchronizesPrivatePrimitiveAndRegisteredCustomFields() {
        var server = new Host();
        server.number = 42;
        server.custom = new CustomValue(1234);
        var client = new Host();

        var serverSync = new DataSynchronization(server);
        var clientSync = new DataSynchronization(client);
        ByteBuf data = Unpooled.buffer();
        try {
            serverSync.writeFull(data);
            clientSync.readUpdate(data);
        } finally {
            data.release();
        }

        assertEquals(42, client.number);
        assertEquals(new CustomValue(1234), client.custom);
        assertFalse(serverSync.hasChanges());
        assertFalse(clientSync.hasChanges());
    }

    private static final class Host {
        @GuiSync(1)
        private int number;

        @GuiSync(2)
        private CustomValue custom = new CustomValue(0);
    }

    private record CustomValue(int value) {
    }

    private static final class CustomValueField extends SynchronizedField {
        private CustomValueField(Object source, VarHandle varHandle) {
            super(source, varHandle);
        }

        @Override
        protected void writeValue(ByteBuf data) {
            data.writeInt(((CustomValue) get()).value());
        }

        @Override
        protected void readValue(ByteBuf data) {
            set(new CustomValue(data.readInt()));
        }
    }
}
