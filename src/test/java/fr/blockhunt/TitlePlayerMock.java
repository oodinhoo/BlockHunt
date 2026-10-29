package fr.blockhunt;

import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.kyori.adventure.title.Title;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import java.util.ArrayDeque;
import java.util.Queue;

/** MockBukkit's player, keeping the Adventure titles it is shown, which MockBukkit drops. */
// shortcut: drop this class once PlayerMock records Adventure titles (it only keeps legacy ones).
class TitlePlayerMock extends PlayerMock {

    private final Queue<String> titles = new ArrayDeque<>();

    TitlePlayerMock(ServerMock server, String name) {
        super(server, name);
    }

    @Override
    public void showTitle(Title title) {
        titles.add(PlainTextComponentSerializer.plainText().serialize(title.title()));
    }

    /** The oldest title not read yet, null if none. */
    String nextAdventureTitle() {
        return titles.poll();
    }
}
