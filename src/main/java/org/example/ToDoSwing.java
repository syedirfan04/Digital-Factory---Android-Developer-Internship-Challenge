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
    // Simple task data
    static class Task {
        int id;               // unique id for this task
        String title;         // short title
        String description;   // longer note
        boolean completed;    // done flag
        Task(int id, String title, String description, boolean completed) {
            this.id = id; this.title = title; this.description = description; this.completed = completed;
        }
    }

    // Store tasks as tab separated lines in a file
    static class TaskStore {
        private final Path file;           // file path like ~/.todo_simple/tasks.txt
        TaskStore(Path file) { this.file = file; }

        // Read all tasks from disk
        java.util.List<Task> load() {
            java.util.List<Task> out = new ArrayList<>();
            try {
                ensureParent();
                if (!Files.exists(file)) return out;      // first run, no file yet
                for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                    if (line.trim().isEmpty()) continue; // skip blank lines
                    String[] parts = line.split("\t", -1);
                    if (parts.length < 4) continue;      // skip bad lines
                    int id = Integer.parseInt(parts[0]);
                    boolean completed = "1".equals(parts[1]);
                    String title = parts[2];
                    String desc = parts[3];
                    out.add(new Task(id, title, desc, completed));
                }
            } catch (Exception ignored) { /* ignore corrupt file content */ }
            return out;
        }

        // Write all tasks to disk
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

        // Create parent folder if missing
        private void ensureParent() throws IOException {
            Path dir = file.getParent();
            if (dir != null && !Files.exists(dir)) Files.createDirectories(dir);
        }

        // Replace tabs and newlines so each task stays on one line
        private static String safe(String s) {
            if (s == null) return "";
            return s.replace('\t', ' ').replace('\n', ' ');
        }
    }

    // In memory operations on tasks
    static class TaskService {
        private final java.util.List<Task> tasks; // live list used by the table model
        private int nextId;                       // next id to assign
        TaskService(java.util.List<Task> initial) {
            this.tasks = new ArrayList<>(initial);
            this.nextId = computeNextId(tasks);
            sort();
        }
        // Read only view for callers
        java.util.List<Task> all() { return Collections.unmodifiableList(tasks); }
        // Add new task at top of list
        Task add(String title, String desc) {
            Task t = new Task(nextId++, title.trim(), desc == null ? "" : desc.trim(), false);
            tasks.add(t); sort(); return t;
        }
        // Set completed flag by id
        boolean toggle(int id, boolean value) {
            for (Task t : tasks) if (t.id == id) { t.completed = value; sort(); return true; }
            return false;
        }
        // Remove by id
        boolean delete(int id) { boolean ok = tasks.removeIf(t -> t.id == id); if (ok) sort(); return ok; }
        // Order incomplete first, then newest first
        private void sort() {
            tasks.sort(Comparator
                    .comparing((Task t) -> t.completed)
                    .thenComparing((Task t) -> t.id, Comparator.reverseOrder()));
        }
        // Find next id based on current max
        private static int computeNextId(java.util.List<Task> ts) {
            int max = 0; for (Task t : ts) max = Math.max(max, t.id); return max + 1;
        }
    }

    // Table model that connects the list of tasks to JTable
    static class TasksTableModel extends AbstractTableModel {
        private final String[] cols = {"Done", "Title", "Description"};
        private final TaskService service;
        TasksTableModel(TaskService service) { this.service = service; }
        @Override public int getRowCount() { return service.all().size(); }
        @Override public int getColumnCount() { return cols.length; }
        @Override public String getColumnName(int c) { return cols[c]; }
        @Override public Class<?> getColumnClass(int c) { return c == 0 ? Boolean.class : String.class; }
        @Override public boolean isCellEditable(int r, int c) { return c == 0; } // only checkbox column is editable
        private Task rowTask(int r) { return service.all().get(r); }
        @Override public Object getValueAt(int r, int c) {
            Task t = rowTask(r);
            return switch (c) {
                case 0 -> t.completed; case 1 -> t.title; case 2 -> t.description; default -> null; };
        }
        @Override public void setValueAt(Object aValue, int r, int c) {
            if (c == 0) {                    // checkbox changed
                Task t = rowTask(r);
                boolean val = (Boolean) aValue;
                if (service.toggle(t.id, val)) {
                    fireTableDataChanged();   // update rows and order
                }
            }
        }
    }

    // Renderer that adds strike through and gray color for completed rows
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

    // UI fields
    private final TaskStore store;
    private final TaskService service;
    private final TasksTableModel model;

    public ToDoSwing() {
        super("To-Do");
        // Build storage and service
        Path file = Paths.get(System.getProperty("user.home"), ".todo_simple", "tasks.txt");
        this.store = new TaskStore(file);
        this.service = new TaskService(store.load());
        this.model = new TasksTableModel(service);

        // Table setup
        JTable table = new JTable(model);
        table.setFillsViewportHeight(true);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.getColumnModel().getColumn(1).setCellRenderer(new StrikeRenderer());
        table.getColumnModel().getColumn(2).setCellRenderer(new StrikeRenderer());
        table.setRowHeight(22);

        JScrollPane scroll = new JScrollPane(table);

        // Buttons
        JButton btnAdd = new JButton("Add");
        JButton btnDelete = new JButton("Delete");

        // Actions
        btnAdd.addActionListener(e -> onAdd());
        btnDelete.addActionListener(e -> onDelete(table));

        // Bottom bar
        JPanel controls = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        controls.add(btnAdd);
        controls.add(btnDelete);

        // Layout
        setLayout(new BorderLayout(8, 8));
        add(scroll, BorderLayout.CENTER);
        add(controls, BorderLayout.SOUTH);

        // Save when checkbox changes
        model.addTableModelListener(ev -> {
            if (ev.getType() == TableModelEvent.UPDATE) {
                store.save(service.all());
            }
        });

        // Window setup
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setSize(640, 400);
        setLocationRelativeTo(null);
    }

    // Show add dialog and create a task
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

    // Delete the selected task after confirmation
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

    // Start the app
    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> new ToDoSwing().setVisible(true));
    }
}
