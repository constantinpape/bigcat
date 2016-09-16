package bdv.bigcat.annotation;

import net.imglib2.RealPoint;

public class PreSynapticSite extends Annotation {

	public PreSynapticSite(long id, RealPoint pos, String comment) {
		super(id, pos, comment);
	}

	public PostSynapticSite getPartner() {
		return partner;
	}

	public void setPartner(PostSynapticSite partner) {
		this.partner = partner;
	}

	@Override
	public void accept(AnnotationVisitor visitor) {
		super.accept(visitor);
		visitor.visit(this);	
	}
	
	@Override
	public String toString() {
		return this.getClass().getName();
	}
	
	private PostSynapticSite partner;
}