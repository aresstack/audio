package com.aresstack.audio.studio.ui;

import com.aresstack.audio.studio.album.Album;
import com.aresstack.audio.studio.album.AlbumMembership;
import com.aresstack.audio.studio.album.AlbumTrack;
import com.aresstack.audio.studio.application.SongLibrary;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;
import java.awt.datatransfer.Transferable;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JTree;
import javax.swing.TransferHandler;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeCellRenderer;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;
import javax.swing.tree.TreeSelectionModel;

/**
 * The albums of the project as a tree at the left of the desk: each album with its titles. A click
 * on an album shows its settings, a click on a title opens its piece; a title can be dragged into
 * another album. Every piece is in an album; pieces without one are in an unnamed album, and there
 * may be several of those.
 */
final class AlbumTree extends JPanel {
    /** What the tree asks of the window. */
    interface Host {
        /** The user clicked an album: show its settings. */
        void albumClicked(String albumId);

        /** The user clicked a title that has a piece: open it. */
        void pieceClicked(String albumId, Path folder);

        /** The user clicked a title that has no piece yet: its piece is made now and opened. */
        default void plannedClicked(String albumId, String trackId) {
            albumClicked(albumId);
        }

        /** The user dragged a title into an album, before the title at {@code index} (-1: at the end). */
        void moveTitle(String fromAlbum, String trackId, String toAlbum, int index);

        void newAlbum();

        void newComposition();

        void deleteAlbum(String albumId);

        /** A new piece in this album. */
        default void newCompositionIn(String albumId) {
            newComposition();
        }

        /** Asks for a new name of the album and gives it. */
        default void renameAlbum(String albumId) {
        }

        /** Asks for a new name of the title and gives it, to its piece as well. */
        default void renameTitle(String albumId, String trackId) {
        }

        /** Asks whether the title (and its piece) should go, and takes it away. */
        default void deleteTitle(String albumId, String trackId) {
        }

        /** Produces the album, as its page does. */
        default void produceAlbum(String albumId) {
            albumClicked(albumId);
        }
    }

    /** A node of an album. */
    record AlbumNode(Album album, String label) {
        @Override
        public String toString() {
            return label;
        }
    }

    /** A node of a title of an album; {@code folder} is {@code null} while it has no piece. */
    record TitleNode(Album album, AlbumTrack track, String name, Path folder) {
        @Override
        public String toString() {
            return name;
        }
    }

    private final Host host;
    private final DefaultMutableTreeNode root = new DefaultMutableTreeNode("Projekt");
    private final DefaultTreeModel model = new DefaultTreeModel(root);
    private final JTree tree = new JTree(model);
    private final JScrollPane scroll;
    private boolean filling;
    /** When the selection last told the window, so that the click that made it does not tell it twice. */
    private long announcedAt = -1;

    AlbumTree(Host host) {
        super(new BorderLayout(0, 8));
        this.host = host;
        setOpaque(false);
        setPreferredSize(new Dimension(250, 200));
        tree.setRootVisible(false);
        tree.setShowsRootHandles(true);
        tree.setRowHeight(24);
        // Part of the desk, like the menu that was here before: the module's own dark, no frame of its own.
        tree.setBackground(AudioStudioTheme.MODULE);
        tree.setForeground(AudioStudioTheme.TEXT);
        tree.setOpaque(true);
        tree.getSelectionModel().setSelectionMode(TreeSelectionModel.SINGLE_TREE_SELECTION);
        tree.setCellRenderer(new DefaultTreeCellRenderer() {
            @Override
            public Component getTreeCellRendererComponent(JTree owner, Object value, boolean selected, boolean expanded,
                                                          boolean leaf, int row, boolean focused) {
                Object shown = value instanceof DefaultMutableTreeNode node ? node.getUserObject() : value;
                setBackgroundNonSelectionColor(AudioStudioTheme.MODULE);
                setTextNonSelectionColor(AudioStudioTheme.TEXT);
                setBackgroundSelectionColor(AudioStudioTheme.RAISED);
                setTextSelectionColor(AudioStudioTheme.TEXT);
                setBorderSelectionColor(AudioStudioTheme.ACCENT);
                Component label = super.getTreeCellRendererComponent(owner, shown, selected, expanded, leaf, row,
                        focused);
                setIcon(null);
                if (shown instanceof AlbumNode) {
                    setFont(owner.getFont().deriveFont(Font.BOLD));
                } else {
                    setFont(owner.getFont().deriveFont(Font.PLAIN));
                    if (shown instanceof TitleNode title && title.folder() == null && !selected) {
                        setForeground(AudioStudioTheme.TEXT_DIM);
                    }
                }
                return label;
            }
        });
        tree.addTreeSelectionListener(event -> {
            if (filling || event.getNewLeadSelectionPath() == null) {
                return;
            }
            Object chosen = ((DefaultMutableTreeNode) event.getNewLeadSelectionPath().getLastPathComponent())
                    .getUserObject();
            announcedAt = java.awt.EventQueue.getMostRecentEventTime();
            if (chosen instanceof AlbumNode album) {
                host.albumClicked(album.album().id());
            } else if (chosen instanceof TitleNode title) {
                if (title.folder() != null) {
                    host.pieceClicked(title.album().id(), title.folder());
                } else {
                    host.plannedClicked(title.album().id(), title.track().id());
                }
            }
        });
        // A second click on what is selected shows it again (the settings, or the piece).
        tree.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mousePressed(java.awt.event.MouseEvent event) {
                TreePath path = tree.getPathForLocation(event.getX(), event.getY());
                if (path == null) {
                    return;
                }
                Object chosen = ((DefaultMutableTreeNode) path.getLastPathComponent()).getUserObject();
                if (javax.swing.SwingUtilities.isRightMouseButton(event) || event.isPopupTrigger()) {
                    menu(chosen).show(tree, event.getX(), event.getY());
                    return;
                }
                if (path.equals(tree.getSelectionPath()) && event.getWhen() != announcedAt) {
                    if (chosen instanceof AlbumNode album) {
                        host.albumClicked(album.album().id());
                    } else if (chosen instanceof TitleNode title && title.folder() != null) {
                        host.pieceClicked(title.album().id(), title.folder());
                    }
                }
            }
        });
        tree.getInputMap(JComponent.WHEN_FOCUSED).put(javax.swing.KeyStroke.getKeyStroke("F2"), "albumTreeRename");
        tree.getActionMap().put("albumTreeRename", action(() -> {
            Object chosen = selected();
            if (chosen instanceof AlbumNode album) {
                host.renameAlbum(album.album().id());
            } else if (chosen instanceof TitleNode title) {
                host.renameTitle(title.album().id(), title.track().id());
            }
        }));
        tree.getInputMap(JComponent.WHEN_FOCUSED).put(javax.swing.KeyStroke.getKeyStroke("DELETE"), "albumTreeDelete");
        tree.getActionMap().put("albumTreeDelete", action(() -> {
            Object chosen = selected();
            if (chosen instanceof AlbumNode album) {
                host.deleteAlbum(album.album().id());
            } else if (chosen instanceof TitleNode title) {
                host.deleteTitle(title.album().id(), title.track().id());
            }
        }));
        if (!java.awt.GraphicsEnvironment.isHeadless()) {
            tree.setDragEnabled(true); // throws HeadlessException without a screen (CI)
        }
        tree.setDropMode(javax.swing.DropMode.ON_OR_INSERT);
        tree.setTransferHandler(new Moving());

        scroll = new JScrollPane(tree);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.getViewport().setBackground(AudioStudioTheme.MODULE);
        scroll.setBackground(AudioStudioTheme.MODULE);
        add(scroll, BorderLayout.CENTER);
        // The two keys stand in the head of the module, beside its name; the tree has the whole height.
        newAlbum.setToolTipText("Neues Album: ein Album anlegen; ohne Titel heißt es „" + AlbumMembership.UNTITLED + "“");
        newAlbum.addActionListener(event -> host.newAlbum());
        newPiece.setToolTipText("Neue Komposition: ein neues Stück im gewählten Album anlegen");
        newPiece.addActionListener(event -> host.newComposition());
    }

    private final JButton newAlbum = new DeckWidgets.HeadKey("+");
    private final JButton newPiece = new DeckWidgets.HeadKey("♪ +");

    /** The key that makes a new album, for the head of the module right after its name. */
    JComponent newAlbumKey() {
        JPanel left = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 0, 0));
        left.setOpaque(false);
        left.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 0));
        left.add(newAlbum);
        return left;
    }

    /** The key that makes a new composition in the chosen album, for the right end of the module's head. */
    JComponent newPieceKey() {
        return newPiece;
    }

    /**
     * Shows the albums with their titles. The albums that were open stay open, a new album is
     * open; the piece in use, or else the album chosen, is selected without telling the window.
     */
    void show(List<Album> albums, SongLibrary songs, String chosenAlbum, Path current) {
        String selectedBefore = key(selected());
        java.awt.Point seen = scroll.getViewport().getViewPosition();
        Set<String> closed = new HashSet<String>();
        Set<String> known = new HashSet<String>();
        for (int i = 0; i < root.getChildCount(); i++) {
            DefaultMutableTreeNode node = (DefaultMutableTreeNode) root.getChildAt(i);
            String id = ((AlbumNode) node.getUserObject()).album().id();
            known.add(id);
            if (!tree.isExpanded(new TreePath(node.getPath()))) {
                closed.add(id);
            }
        }
        filling = true;
        try {
            root.removeAllChildren();
            Path wanted = current == null ? null : current.toAbsolutePath().normalize();
            DefaultMutableTreeNode select = null;
            java.util.Map<String, String> labels = AlbumMembership.labels(albums);
            for (Album album : albums) {
                String label = labels.get(album.id());
                DefaultMutableTreeNode albumNode = new DefaultMutableTreeNode(new AlbumNode(album,
                        label + "   ·   " + album.tracks().size()));
                root.add(albumNode);
                for (AlbumTrack track : album.tracks()) {
                    Path folder = track.hasPiece() ? songs.root().resolve(track.piece()) : null;
                    if (folder != null && !Files.isDirectory(folder)) {
                        folder = null;
                    }
                    String name = folder == null ? track.title() + "   (geplant)" : songs.name(folder);
                    DefaultMutableTreeNode titleNode = new DefaultMutableTreeNode(new TitleNode(album, track, name,
                            folder));
                    albumNode.add(titleNode);
                    if (wanted != null && folder != null && folder.toAbsolutePath().normalize().equals(wanted)
                            && (select == null || album.id().equals(chosenAlbum))) {
                        select = titleNode;
                    }
                }
                if (select == null && album.id().equals(chosenAlbum) && wanted == null) {
                    select = albumNode;
                }
            }
            model.reload();
            for (int i = 0; i < root.getChildCount(); i++) {
                DefaultMutableTreeNode node = (DefaultMutableTreeNode) root.getChildAt(i);
                String id = ((AlbumNode) node.getUserObject()).album().id();
                if (!closed.contains(id) || !known.contains(id)) {
                    tree.expandPath(new TreePath(node.getPath()));
                }
            }
            // Where the user scrolled stays: the list jumps to the selection only when that is a new one.
            scroll.getViewport().setViewPosition(seen);
            if (select != null) {
                TreePath path = new TreePath(select.getPath());
                tree.setSelectionPath(path);
                if (!key(select.getUserObject()).equals(selectedBefore)) {
                    tree.scrollPathToVisible(path);
                }
            } else {
                tree.clearSelection();
            }
        } finally {
            filling = false;
        }
    }

    /** Selects an album without telling the window, as when its settings are shown. */
    void selectAlbum(String id) {
        Enumeration<?> nodes = root.children();
        while (nodes.hasMoreElements()) {
            DefaultMutableTreeNode node = (DefaultMutableTreeNode) nodes.nextElement();
            if (((AlbumNode) node.getUserObject()).album().id().equals(id)) {
                filling = true;
                try {
                    tree.setSelectionPath(new TreePath(node.getPath()));
                } finally {
                    filling = false;
                }
                return;
            }
        }
    }

    /** What is selected: an album node, a title node, or {@code null}. */
    private Object selected() {
        TreePath path = tree.getSelectionPath();
        return path == null ? null : ((DefaultMutableTreeNode) path.getLastPathComponent()).getUserObject();
    }

    /** What a node stands for, so that the same album or title is known again after a reload. */
    private static String key(Object node) {
        if (node instanceof AlbumNode album) {
            return "a:" + album.album().id();
        }
        if (node instanceof TitleNode title) {
            return "t:" + title.album().id() + "/" + title.track().id();
        }
        return "";
    }

    private static javax.swing.Action action(Runnable run) {
        return new javax.swing.AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent event) {
                run.run();
            }
        };
    }

    private static JMenuItem item(String text, Runnable run) {
        JMenuItem item = new JMenuItem(text);
        item.addActionListener(event -> run.run());
        return item;
    }

    /** The menu of the right mouse key, for an album or a title. */
    JPopupMenu menu(Object chosen) {
        JPopupMenu menu = new JPopupMenu();
        if (chosen instanceof AlbumNode node) {
            String id = node.album().id();
            menu.add(item("Album öffnen", () -> host.albumClicked(id)));
            menu.add(item("Neue Komposition in diesem Album …", () -> host.newCompositionIn(id)));
            menu.add(item("Album produzieren", () -> host.produceAlbum(id)));
            menu.addSeparator();
            menu.add(item("Umbenennen …   (F2)", () -> host.renameAlbum(id)));
            menu.add(item("Album löschen …   (Entf)", () -> host.deleteAlbum(id)));
        } else if (chosen instanceof TitleNode title) {
            String album = title.album().id();
            String track = title.track().id();
            menu.add(item(title.folder() == null ? "Komposition anlegen und öffnen" : "Komposition öffnen", () -> {
                if (title.folder() != null) {
                    host.pieceClicked(album, title.folder());
                } else {
                    host.plannedClicked(album, track);
                }
            }));
            menu.add(item("Umbenennen …   (F2)", () -> host.renameTitle(album, track)));
            javax.swing.JMenu into = new javax.swing.JMenu("In Album verschieben");
            for (int i = 0; i < root.getChildCount(); i++) {
                AlbumNode other = (AlbumNode) ((DefaultMutableTreeNode) root.getChildAt(i)).getUserObject();
                if (!other.album().id().equals(album)) {
                    String label = other.label().replaceAll("\\s+·\\s+\\d+$", "");
                    into.add(item(label, () -> host.moveTitle(album, track, other.album().id(), -1)));
                }
            }
            into.setEnabled(into.getItemCount() > 0);
            menu.add(into);
            menu.addSeparator();
            menu.add(item("Titel löschen …   (Entf)", () -> host.deleteTitle(album, track)));
        }
        return menu;
    }

    /** The titles shown, as "album id / title", for a test. */
    List<String> shown() {
        List<String> lines = new ArrayList<String>();
        for (int i = 0; i < root.getChildCount(); i++) {
            DefaultMutableTreeNode node = (DefaultMutableTreeNode) root.getChildAt(i);
            lines.add(node.getUserObject().toString());
            for (int j = 0; j < node.getChildCount(); j++) {
                lines.add("  " + ((DefaultMutableTreeNode) node.getChildAt(j)).getUserObject());
            }
        }
        return lines;
    }

    JTree tree() {
        return tree;
    }

    /** Dragging a title into another album, or to another place in its own. */
    private final class Moving extends TransferHandler {
        @Override
        public int getSourceActions(JComponent component) {
            return MOVE;
        }

        @Override
        protected Transferable createTransferable(JComponent component) {
            TreePath path = tree.getSelectionPath();
            if (path == null) {
                return null;
            }
            Object chosen = ((DefaultMutableTreeNode) path.getLastPathComponent()).getUserObject();
            if (!(chosen instanceof TitleNode title)) {
                return null;
            }
            return new StringSelection(title.album().id() + "\n" + title.track().id());
        }

        @Override
        public boolean canImport(TransferSupport support) {
            if (!support.isDrop() || !support.isDataFlavorSupported(DataFlavor.stringFlavor)) {
                return false;
            }
            return target(support) != null;
        }

        @Override
        public boolean importData(TransferSupport support) {
            if (!canImport(support)) {
                return false;
            }
            String[] dragged;
            try {
                dragged = ((String) support.getTransferable().getTransferData(DataFlavor.stringFlavor)).split("\n");
            } catch (Exception unreadable) {
                return false;
            }
            if (dragged.length != 2) {
                return false;
            }
            Object[] at = target(support);
            host.moveTitle(dragged[0], dragged[1], (String) at[0], (Integer) at[1]);
            return true;
        }

        /** The album dropped into and the place in it (-1: at the end), or {@code null} for no album. */
        private Object[] target(TransferSupport support) {
            JTree.DropLocation drop = (JTree.DropLocation) support.getDropLocation();
            if (drop.getPath() == null) {
                return null;
            }
            DefaultMutableTreeNode node = (DefaultMutableTreeNode) drop.getPath().getLastPathComponent();
            Object chosen = node.getUserObject();
            if (chosen instanceof AlbumNode album) {
                return new Object[]{album.album().id(), drop.getChildIndex()};
            }
            if (chosen instanceof TitleNode title) {
                // Dropped onto a title: into its album, before it.
                return new Object[]{title.album().id(), node.getParent().getIndex(node)};
            }
            return null;
        }
    }
}
