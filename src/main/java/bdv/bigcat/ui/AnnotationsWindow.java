package bdv.bigcat.ui;

import java.util.LinkedList;
import java.util.List;

import javax.swing.JFrame;
import javax.swing.JScrollPane;
import javax.swing.table.AbstractTableModel;

import net.imglib2.RealPoint;
import bdv.bigcat.annotation.Annotation;
import bdv.bigcat.annotation.Annotations;
import bdv.bigcat.annotation.PostSynapticSite;
import bdv.bigcat.annotation.PreSynapticSite;
import bdv.bigcat.annotation.Synapse;

public class AnnotationsWindow extends JFrame {

	private static final long serialVersionUID = 1L;

	private final Annotations annotations;
	private final BigCatTable table;

	class AnnotationsTableModel extends AbstractTableModel {

		private static final long serialVersionUID = 1L;

		public static final int ID_INDEX = 0;
		public static final int TYPE_INDEX = 1;
		public static final int LOCATION_X_INDEX = 2;
		public static final int LOCATION_Y_INDEX = 3;
		public static final int LOCATION_Z_INDEX = 4;
		public static final int COMMENT_INDEX = 5;
		public final String[] ColumnNames = { "id", "type", "x", "y", "z", "comment" };

		public List<Long> ids;

		public AnnotationsTableModel() {

			ids = new LinkedList<Long>();
			for (Annotation a : annotations.getAnnotations())
				ids.add(a.getId());
		}

		public String getColumnName(int column) {

			return ColumnNames[column];
		}

		public boolean isCellEditable(int row, int column) {

			if (column == COMMENT_INDEX)
				return true;
			return false;
		}

		public Class<?> getColumnClass(int column) {

			switch (column) {

			case ID_INDEX:
				return Long.class;
			case TYPE_INDEX:
				return String.class;
			case LOCATION_X_INDEX:
			case LOCATION_Y_INDEX:
			case LOCATION_Z_INDEX:
				return Integer.class;
			case COMMENT_INDEX:
				return String.class;
			default:
				return Object.class;
			}
		}

		public Object getValueAt(int row, int column) {

			Long id = ids.get(row);

			if (column == ID_INDEX)
				return id;

			Annotation a = annotations.getById(id);

			switch (column) {

			case TYPE_INDEX:
				return toTypeString(a);
			case LOCATION_X_INDEX:
				return (int)Math.round(a.getPosition().getFloatPosition(0));
			case LOCATION_Y_INDEX:
				return (int)Math.round(a.getPosition().getFloatPosition(1));
			case LOCATION_Z_INDEX:
				return (int)Math.round(a.getPosition().getFloatPosition(2));
			case COMMENT_INDEX:
				return a.getComment();
			default:
				return new Object();
			}
		}

		public void setValueAt(Object value, int row, int column) {

			if (column != COMMENT_INDEX)
				return;

			Long id = ids.get(row);
			Annotation a = annotations.getById(id);

			a.setComment((String) value);

			fireTableCellUpdated(row, column);
		}

		public int getRowCount() {

			return ids.size();
		}

		public int getColumnCount() {

			return ColumnNames.length;
		}
	}

	public AnnotationsWindow(Annotations annotations) {

		this.annotations = annotations;

		table = new BigCatTable(new AnnotationsTableModel());
		JScrollPane scrollPane = new JScrollPane(table);
		getContentPane().add(scrollPane);
		setDefaultCloseOperation(JFrame.HIDE_ON_CLOSE);
		pack();
		setSize(500, 800);
	}

	private String toTypeString(Annotation a) {

		if (a instanceof Synapse)
			return "synapse";
		if (a instanceof PreSynapticSite)
			return "presynaptic_site";
		if (a instanceof PostSynapticSite)
			return "postsynaptic_site";
		throw new RuntimeException("unknown annotation class " + a.getClass());
	}
}
