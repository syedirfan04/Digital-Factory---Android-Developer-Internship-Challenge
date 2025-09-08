// File: src/main/java/org/example/ToDoSwing.java
// Swing To-Do popup app — add / complete / delete with persistence (no external libs)
package org.example;

import javax.swing.*;
import javax.swing.event.TableModelEvent;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import java.awt.*;
import java.awt.font.TextAttribute;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

public class ToDoSwing extends JFrame {
    /* ===== Model ===== */
    static class Task {
        int id;               // why: stable identity for toggle/delete
        String title;
        String description;
        boolean completed;
        Task(int id, String title, String description, boolean completed) {
            this.id = id; this.title = title; this.description = description; this.completed = completed;
        }
    }

    /* ===== Persistence (plain text: id \t completed(0/1) \t title \t description) ===== */
    static class TaskStore {
        private final Path file;
        TaskStore(Path file) { this.file = file; }

        java.util.List<Task> load() {
            java.util.List<Task> out = new ArrayList<>();
            try {
                ensureParent();
                if (!Files.exists(file)) return out;
                for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                    if (line.trim().isEmpty()) continue;
                    String[] parts = line.split("\t", -1);
                    if (parts.length < 4) continue; // skip corrupt rows
                    int id = Integer.parseInt(parts[0]);
                    boolean completed = "1".equals(parts[1]);
                    String title = parts[2];
                    String desc = parts[3];
                    out.add(new Task(id, title, desc, completed));
                }
            } catch (Exception ignored) { /* tolerate corrupt file */ }
            return out;
        }

        void save(java.util.List<Task> tasks) {
            try {
                ensureParent();
                try (BufferedWriter w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                    for (Task t : tasks) {
                        w.write(t.id + "\t" + (t.completed ? "1" : "0") + "\t" + safe(t.title) + "\t" + safe(t.description));
                        w.newLine();
                    }
                }
            } catch (IOException e) {
                JOptionPane.showMessageDialog(null, "Failed to save: " + e.getMessage(), "Warning", JOptionPane.WARNING_MESSAGE);
            }
        }

        private void ensureParent() throws IOException {
            Path dir = file.getParent();
            if (dir != null && !Files.exists(dir)) Files.createDirectories(dir);
        }

        private static String safe(String s) {
            if (s == null) return "";
            return s.replace('\t', ' ').replace('\n', ' ');
        }
    }

    /* ===== Service (in-memory list + operations) ===== */
    static class TaskService {
        private final java.util.List<Task> tasks;
        private int nextId;
        TaskService(java.util.List<Task> initial) {
            this.tasks = new ArrayList<>(initial);
            this.nextId = computeNextId(tasks);
            sort();
        }
        java.util.List<Task> all() { return Collections.unmodifiableList(tasks); }
        Task add(String title, String desc) {
            Task t = new Task(nextId++, title.trim(), desc == null ? "" : desc.trim(), false);
            tasks.add(t); sort(); return t;
        }
        boolean toggle(int id, boolean value) {
            for (Task t : tasks) if (t.id == id) { t.completed = value; sort(); return true; }
            return false;
        }
        boolean delete(int id) { boolean ok = tasks.removeIf(t -> t.id == id); if (ok) sort(); return ok; }
        private void sort() {
            // show incomplete first, then newest by id
            tasks.sort(Comparator
                    .comparing((Task t) -> t.completed)
                    .thenComparing((Task t) -> t.id, Comparator.reverseOrder()));
        }
        private static int computeNextId(java.util.List<Task> ts) {
            int max = 0; for (Task t : ts) max = Math.max(max, t.id); return max + 1;
        }
    }

    /* ===== Table Model ===== */
    static class TasksTableModel extends AbstractTableModel {
        private final String[] cols = {"Done", "Title", "Description"};
        private final TaskService service;
        TasksTableModel(TaskService service) { this.service = service; }
        @Override public int getRowCount() { return service.all().size(); }
        @Override public int getColumnCount() { return cols.length; }
        @Override public String getColumnName(int c) { return cols[c]; }
        @Override public Class<?> getColumnClass(int c) { return c == 0 ? Boolean.class : String.class; }
        @Override public boolean isCellEditable(int r, int c) { return c == 0; } // only checkbox
        private Task rowTask(int r) { return service.all().get(r); }
        @Override public Object getValueAt(int r, int c) {
            Task t = rowTask(r);
            return switch (c) {
                case 0 -> t.completed; case 1 -> t.title; case 2 -> t.description; default -> null; };
        }
        @Override public void setValueAt(Object aValue, int r, int c) {
            if (c == 0) {
                Task t = rowTask(r);
                boolean val = (Boolean) aValue;
                if (service.toggle(t.id, val)) {
                    fireTableDataChanged();
                }
            }
        }
    }

    /* ===== Strike-through Renderer for Title/Description ===== */
    static class StrikeRenderer extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected, boolean hasFocus, int row, int column) {
            Component c = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
            boolean completed = Boolean.TRUE.equals(table.getModel().getValueAt(row, 0));
            Font base = c.getFont();
            Map<TextAttribute, Object> attrs = new HashMap<>(base.getAttributes());
            if (completed) {
                attrs.put(TextAttribute.STRIKETHROUGH, TextAttribute.STRIKETHROUGH_ON);
                c.setForeground(isSelected ? table.getSelectionForeground() : Color.GRAY);
            } else {
                attrs.remove(TextAttribute.STRIKETHROUGH);
                c.setForeground(isSelected ? table.getSelectionForeground() : table.getForeground());
            }
            c.setFont(base.deriveFont(attrs));
            return c;
        }
    }

    /* ===== UI ===== */
    private final TaskStore store;
    private final TaskService service;
    private final TasksTableModel model;

    public ToDoSwing() {
        super("To-Do");
        Path file = Paths.get(System.getProperty("user.home"), ".todo_simple", "tasks.txt");
        this.store = new TaskStore(file);
        this.service = new TaskService(store.load());
        this.model = new TasksTableModel(service);

        JTable table = new JTable(model);
        table.setFillsViewportHeight(true);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.getColumnModel().getColumn(1).setCellRenderer(new StrikeRenderer());
        table.getColumnModel().getColumn(2).setCellRenderer(new StrikeRenderer());
        table.setRowHeight(22);

        JScrollPane scroll = new JScrollPane(table);

        JButton btnAdd = new JButton("Add");
        JButton btnDelete = new JButton("Delete");

        btnAdd.addActionListener(e -> onAdd());
        btnDelete.addActionListener(e -> onDelete(table));

        JPanel controls = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        controls.add(btnAdd);
        controls.add(btnDelete);

        setLayout(new BorderLayout(8, 8));
        add(scroll, BorderLayout.CENTER);
        add(controls, BorderLayout.SOUTH);

        model.addTableModelListener(ev -> {
            if (ev.getType() == TableModelEvent.UPDATE) {
                store.save(service.all());
            }
        });

        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setSize(640, 400);
        setLocationRelativeTo(null);
    }

    private void onAdd() {
        JTextField tfTitle = new JTextField(20);
        JTextArea taDesc = new JTextArea(5, 20);
        taDesc.setLineWrap(true);
        taDesc.setWrapStyleWord(true);
        JScrollPane sp = new JScrollPane(taDesc);

        JPanel panel = new JPanel(new GridBagLayout());
        GridBagConstraints gc = new GridBagConstraints();
        gc.insets = new Insets(4,4,4,4);
        gc.gridx = 0; gc.gridy = 0; gc.anchor = GridBagConstraints.WEST; panel.add(new JLabel("Title:"), gc);
        gc.gridx = 1; gc.fill = GridBagConstraints.HORIZONTAL; gc.weightx = 1; panel.add(tfTitle, gc);
        gc.gridx = 0; gc.gridy = 1; gc.fill = GridBagConstraints.NONE; gc.weightx = 0; panel.add(new JLabel("Description:"), gc);
        gc.gridx = 1; gc.gridy = 1; gc.fill = GridBagConstraints.BOTH; gc.weightx = 1; gc.weighty = 1; panel.add(sp, gc);

        int res = JOptionPane.showConfirmDialog(this, panel, "Add Task", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (res == JOptionPane.OK_OPTION) {
            String title = tfTitle.getText().trim();
            if (title.isEmpty()) {
                JOptionPane.showMessageDialog(this, "Title is required.");
                return;
            }
            String desc = taDesc.getText();
            service.add(title, desc);
            store.save(service.all());
            model.fireTableDataChanged();
        }
    }

    private void onDelete(JTable table) {
        int row = table.getSelectedRow();
        if (row < 0) { JOptionPane.showMessageDialog(this, "Select a row to delete."); return; }
        int modelRow = row; // no sorter used
        Task t = service.all().get(modelRow);
        int res = JOptionPane.showConfirmDialog(this, "Delete: " + t.title + "?", "Confirm", JOptionPane.OK_CANCEL_OPTION);
        if (res == JOptionPane.OK_OPTION) {
            if (service.delete(t.id)) {
                store.save(service.all());
                model.fireTableDataChanged();
            }
        }
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> new ToDoSwing().setVisible(true));
    }
}
