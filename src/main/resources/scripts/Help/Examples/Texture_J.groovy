/*
3D_HaT

This plugin measures Haralick textures in 3 dimensions.
The calculation of GLCM is performed in 13-directions of each pixel, then symmetrized and normalized according Löfstedt et al.
The calculation of Haralick features is performed according to according Löfstedt et al.

The plugin has been developped to work on any images read by bioformat.

Update : 
-Add of a ROI possibility
 


Julien Dumont

November 2020


*/

#@ ImagePlus (required=false) singleimg
#@ OpService ops
#@ ResultsTable rt


import net.imagej.ops.image.cooccurrenceMatrix.MatrixOrientation3D
import net.imagej.ops.image.cooccurrenceMatrix.MatrixOrientation2D
import net.imglib2.img.array.ArrayImgs
import net.imglib2.img.display.imagej.ImageJFunctions
import net.imglib2.type.numeric.real.DoubleType
import net.imglib2.Cursor
import net.imglib2.IterableInterval
import ij.plugin.ImageCalculator
import ij.IJ
import loci.plugins.BF
import loci.formats.ImageReader
import loci.formats.MetadataTools
import loci.plugins.in.ImporterOptions
import net.imglib2.type.numeric.integer.LongType
import groovy.time.TimeDuration
import groovy.time.TimeCategory
import ij.gui.GenericDialog
import fiji.util.gui.GenericDialogPlus
import java.awt.Button
import java.awt.Panel
import java.awt.event.ActionEvent
import java.awt.event.*
import groovy.json.JsonSlurper
import ij.gui.WaitForUserDialog
import ij.plugin.frame.RoiManager
import org.scijava.plugin.Parameter
import org.scijava.plugin.Plugin
import org.scijava.command.Command

@Plugin(type = Command.class, menuPath = "Plugins>PreProcessing")

def main(){
	GUI()
	
	if(gdp.wasCanceled() == true){
		return
	}
	//Verification of the radii index and retrieve of the distance
	if (gdp.wasOKed()){
		if(radii==0){
			IJ.log("Error : A radius must be selected.")
			GUI()
		}
		//Extraction of the radius
		if(radii==1){
			flop 	= radiusdiag.getNextNumber()
			flip 	= "["+ flop +"]"
			radius 	= new JsonSlurper().parseText(flip)
			
		}
		else{
			radius 	= radiusdiag.getNextString()
			flip	= "["+ radius +"]"
			radius 	= new JsonSlurper().parseText(flip)
		}
	}
	
	if (singleimg == null){
		srcFile 	= new File(gdp.getNextString())
		
	}
	dstFile 	= new File(gdp.getNextString())
	filename = gdp.getNextString()
	quant 	= gdp.getNextNumber() 
	roi = gdp.getCheckboxes().get(0).getState()
	//Argument checking
	//Quantization factor check
	if(quant==0 || quant%2!=0 && quant!=1){
		IJ.log("ERROR : Quantization should be even or equal to 1, and can't be 0.\nScript has been stopped.")
		return
	}
	

	//Single image analysis
	
	if (singleimg != null) {
		//Folder to save datas
		imp = singleimg
		title = imp.getTitle()
		if(imp.getClass().toString() != "class ij.ImagePlus"){
			IJ.log("Snapshot " + title +  " not compatible with 2D-analysis : Multicolor image detected")
			return -1
		}	
		if(filename != null){
			filepath = dstFile.toString()+ File.separator + filename+ File.separator
			feature = filepath + File.separator + title + "_GLCM_feature.xls"
		}
		else{
			filepath = dstFile.toString()+ File.separator + title + "_d=" + radius+ "_q=" + quant + File.separator
			feature = filepath + File.separator + "GLCM_feature.xls"
		}
		File outfolder = new File(filepath)
		if( !outfolder.exists()) {
				outfolder.mkdirs()
		}
		
		shorttitle = imp.getShortTitle()
		nGLCalc(imp, quant)
		//Quantization of the image 
		if(roi == true){
			rm = new RoiManager()
			rm.reset()
			rm.getRoiManager()
			wfud = new WaitForUserDialog("ROI", "Draw ROI and add them to the ROIManager.\nClick \"OK\" when you're done, or \"Esc\" to quit.")
			wfud.show()
			
			if(wfud.escPressed){
				return
			}
			Roisave = outfolder.toString() + title +".zip"
			rm.runCommand("Save", Roisave)
			for(r=0; r<rm.getCount(); r++ ){
				rm.select(r)
				Roinumb = r
				img = imp.crop("stack")
				for(Ndist = 0; Ndist<radius.size; Ndist++){
					GLCMprocess(img, nGL, radius[Ndist]) 
					calculus(GLCM, nGL, radius[Ndist], outfolder)
					result(title, Roinumb, quant, radius[Ndist], contrast, energy, entropy, homogeneity, correlation)
				}
				
			}
		}
		else{
			Roinumb = "NA"
			for(Ndist = 0; Ndist<radius.size; Ndist++){
				GLCMprocess(imp, nGL, radius[Ndist]) 
				calculus(GLCM, nGL, radius[Ndist], outfolder)
				result(title, Roinumb, quant, radius[Ndist], contrast, energy, entropy, homogeneity, correlation)
			}
		}
		rt.save(feature)
		IJ.selectWindow("Results")
		IJ.run("Close")
		IJ.log("The processing of the image is over.")
  		return
	}
	
	
	//Batch analysis
		
	srcFile.eachFile{
		name = it.getName()
		try{
			reader = new ImageReader()
			path = it.toString()
			reader.setId(path)
			seriesCount = reader.getSeriesCount()
		}
		catch(Exception ex){
			//IJ.log("WARNING: " + name + " could not be processed, as it is not an image.")
			return -1
		}
		for (k=0; k<seriesCount; k++ ){
			openimage(path, k)
			img = imps[0]
			if(img.getClass().toString() != "class ij.ImagePlus"){
				IJ.log("Snapshot " + title +  " not compatible with 2D-analysis : Multicolor image detected")
				return -1
			}
			title = img.getTitle()
			shorttitle = img.getShortTitle()

			Roinumb = "NA"
			//Folder to save datas
			
			if(filename != null){
				filepath = dstFile.toString()+ File.separator + filename+ File.separator
				feature = filepath + File.separator + title + "_GLCM_feature.xls"
			}
			else{
				filepath = dstFile.toString()+ File.separator + title + "_d=" + flip + "_q=" + quant + File.separator
				feature = filepath + File.separator + "GLCM_feature.xls"
			}
			File outfolder = new File(filepath)
			if( !outfolder.exists()) {
 				outfolder.mkdirs()
			}
			
			
			//Calculation of the number of grey-level
			
			nGLCalc(img, quant)
			for(Ndist = 0; Ndist<radius.size; Ndist++){
				GLCMprocess(img, nGL, radius[Ndist]) 
				calculus(GLCM, nGL, radius[Ndist], outfolder)
				result(title, Roinumb, quant, radius[Ndist], contrast, energy, entropy, homogeneity, correlation)
			}
			rt.save(feature)
			IJ.selectWindow("Results")
			IJ.run("Close")
			
		}
		
		
	}
	IJ.log("The processing of images is over.")

}

def GUI(){
	gdp 	= new GenericDialogPlus("3D Haralick Texture plugin")
	panel 	= new Panel()
	
	if (singleimg == null){
		gdp.addDirectoryField("Input directory", ".")
		
	}
	gdp.addDirectoryField("Output directory", ".")
	gdp.addStringField("Ouput filename", "", 30)
	Button singledist 	= new Button("Single radius")
	Button multipledist = new Button("Multiple radiuses")
	panel.add(singledist)
	panel.add(multipledist)
	radii = 0
	singledist.addActionListener(new ActionListener() {
	    @Override
	    public void actionPerformed(ActionEvent e) {
	        radiusdiag = new GenericDialogPlus("Single radius used")
	        radiusdiag.addNumericField("Radius", 1, 0)
	        radiusdiag.showDialog()
	        radii = 1
	    }
	})
	multipledist.addActionListener(new ActionListener() {
	    @Override
	    public void actionPerformed(ActionEvent e) {
	        radiusdiag = new GenericDialogPlus("Multiple radiuses used")
	        radiusdiag.addStringField("Radiuses values separated using commas", "1,3,5,8", 30)
	        radiusdiag.showDialog()
	        radii = 2
	    }
	})
		
	gdp.addPanel(panel)
	gdp.addNumericField("Quantization", 1, 0)
	if (singleimg != null){
		gdp.addCheckbox("Crop a Region Of Interest ?", false)
		
	}
	
	gdp.addHelp("<html>"+
	"<b>Contrast : </b><br></br>"+
	"Contrast  measures  the  quantity  of  local  changes  in  an  image.<br/> Large contrast means coarse texture, while low contrast reflects acute texture.<br></br>"+
	"<b>Energy : </b><br></br>"+
	"Energy measures the homogeneity of the image.<br></br>"+
	"<b>Entropy : </b><br></br>"+
	"Entropy measure of randomness of intensity image.<br></br>"+
	"<b>Homogeneity : </b><br></br>"+
	"Homogeneity measures  the  similarity  of  pixels.<br></br>"+
	"<b>Correlation : </b><br></br>"+
	"Correlation measures how correlated a pixel is to its neighborhood.<br></br>"+
	"<br></br>This work is based on :<li><a href=https://www.researchgate.net/publication/331289061_Gray-level_invariant_Haralick_texture_features>Löfstedt et al.,Gray-level invariant Haralick texture features. PLOS ONE, 2019, 14. e0212110. 10.1371/journal.pone.0212110." + 
	"</a></li><li><a href=https://ieeexplore.ieee.org/document/4309314>R. M. Haralick et al., Textural Features for Image Classification, IEEE Transactions on Systems, Man, and Cybernetics, Nov. 1973</a></li>" + 
	"<br></br><br></br>Plugin designed by Julien Dumont" +
	"</html>")
	gdp.showDialog()
}



def openimage (file, Nserie) {
	options = new ImporterOptions()
	options.setId(file)
	options.setSeriesOn(Nserie, true)
	imps = BF.openImagePlus(options)
	return imps[0]
}
def nGLCalc(img, quant){
	bit = img.getBitDepth()
	nGL = (2**bit)/quant
	nGL = nGL.intValue()
	return nGL
}


def GLCMprocess(img, nGL, distance) {

	zslice = img.getNSlices()
	//Quantization of the image 
	IJ.run(img, "Divide...", "value=" + quant +" stack")
	//Calculus of the co-occurence matrix in the 4(2D) or 13(3D) directions. Return stack of GLCM and number of grey-level

	if (zslice != 1) {
		orientation = [MatrixOrientation3D.HORIZONTAL, MatrixOrientation3D.VERTICAL, MatrixOrientation3D.DIAGONAL,
						MatrixOrientation3D.ANTIDIAGONAL, MatrixOrientation3D.HORIZONTAL_VERTICAL, MatrixOrientation3D.HORIZONTAL_DIAGONAL,
						MatrixOrientation3D.VERTICAL_VERTICAL, MatrixOrientation3D.VERTICAL_DIAGONAL, MatrixOrientation3D.DIAGONAL_VERTICAL,
						MatrixOrientation3D.DIAGONAL_DIAGONAL, MatrixOrientation3D.ANTIDIAGONAL_VERTICAL, MatrixOrientation3D.ANTIDIAGONAL_DIAGONAL,
						MatrixOrientation3D.DEPTH]
	}
	else{	
		orientation = [MatrixOrientation2D.HORIZONTAL, MatrixOrientation2D.VERTICAL, MatrixOrientation2D.DIAGONAL, MatrixOrientation2D.ANTIDIAGONAL]
	}
	long[] dimensions = [nGL, nGL, orientation.size()]
	GLCM = ArrayImgs.doubles(dimensions)
	cursor = GLCM.randomAccess()

	for (z = 0; z < orientation.size(); z++){
		result = ops.run("image.cooccurrenceMatrix", img, nGL, distance, orientation[z])
		for(y = 0; y < nGL; y++){
			for(x = 0; x < nGL; x++){
				cursor.setPosition(x, 0)
				cursor.setPosition(y, 1)
				cursor.setPosition(z, 2)
				value = result[x][y]
				cursor.get().set(value)

			}
		}
	}
	//img.close()
	if (zslice != 1) {
		GLCM = ops.run("math.divide", GLCM, 13)
	}
	else{
		GLCM = ops.run("math.divide", GLCM, 4)
	}
	return [GLCM, nGL]
}



def calculus(GLCM, nGL, distance, folder){

	//Differentials
	dA = 1/(Math.pow(nGL, 2))
	dU = 1/nGL

	//Projection along Z dimension to sum different orientation
	projectOp = ops.op("stats.sum", GLCM)
	GLCMproj = ArrayImgs.doubles(nGL, nGL)
	GLCMproj = ops.run("transform.project", GLCMproj, GLCM, projectOp, 2)

	prout = ImageJFunctions.wrap(GLCMproj, "prout")
	IJ.save(prout, folder.toString() + File.separator + shorttitle + "_GLCM_original"+".tif" )

	
	//Invariant normalization of the symmetrical GLCM
	try{
		GLCMfin = ops.run("math.divide", GLCMproj, dA)
	}
	catch(Exception ex){
		GLCMproj = ImageJFunctions.wrap(GLCMproj)
		GLCMfin = ops.run("math.divide", GLCMproj, dA)
	}
	GLCM = ImageJFunctions.wrap(GLCMfin, "GLCM")
	IJ.save(GLCM, folder.toString() + File.separator + shorttitle + "_GLCM_d=" + distance + ".tif" )
	

	//Calcul de Pxi(i) => Somme de la GLCM dans la dimension Y
	rand = GLCMfin.randomAccess()
	Pxi = [nGL]
	for(x=0; x<nGL; x++){
		Pxi[x] = 0
		for(y=0; y<nGL; y++){
			rand.setPosition(x,0)
			rand.setPosition(y,1)
			value = rand.get().get()
			Pxi[x] += value*dU
		}
	}

	//Calcul de Pyi(i) => Somme de la GLCM dans la dimension X
	rand = GLCMfin.randomAccess()
	Pyi = [nGL]
	for(y=0; y<nGL; y++){
		Pyi[y] = 0
		for(x=0; x<nGL; x++){
			rand.setPosition(x,0)
			rand.setPosition(y,1)
			value = rand.get().get()
			Pyi[y] += value*dU
		}
	}

	//Calcul de mux
	mux = 0
	for(i=0; i<nGL; i++){
		mux += (i/nGL)*Pxi[i]*dU
	}

	//Calcul de muy
	muy = 0
	for(i=0; i<nGL; i++){
		muy += (i/nGL)*Pyi[i]*dU
	}

	//Calcul de sigx
	sigx = 0
	for(i=0; i<nGL; i++){
		sigx += Math.pow(((i/nGL)-mux),2)*Pxi[i]*dU
	}

	//Calcul de sigy
	sigy = 0
	for(i=0; i<nGL; i++){
		sigy += Math.pow(((i/nGL)-muy),2)*Pyi[i]*dU
	}

	//Calcul du contraste

	curproj = GLCMfin.cursor()
	contrast = 0
	for(i=0; i<nGL; i++){
		for(j=0; j<nGL; j++){
			curproj.fwd()
			value = curproj.get().get()
			contrast += (Math.pow((i/nGL - j/nGL),2) * value * dA)
		}
	}
	//Calcul de l'énergie
	curproj = GLCMfin.cursor()
	energy = 0
	for(i=0; i<nGL; i++){
		for(j=0; j<nGL; j++){
			curproj.fwd()
			value = curproj.get().get()
			energy += Math.pow(value,2) * dA
		}
	}
	//Calcul de l'entropie

	curproj = GLCMfin.cursor()
	entropy = 0
	for(i=0; i<nGL; i++){
		for(j=0; j<nGL; j++){
			curproj.fwd()
			value = curproj.get().get()
			calcul = -value * Math.log(value)*dA
			if(calcul.isNaN()==false){
				entropy += calcul
			}
		}
	}
	//Calcul de l'homogénéité

	curproj = GLCMfin.cursor()
	homogeneity = 0
	for(i=0; i<nGL; i++){
		for(j=0; j<nGL; j++){
			curproj.fwd()
			value = curproj.get().get()

			homogeneity += (value/(1 + Math.pow((i/nGL - j/nGL),2))) * dA
		}
	}
	// Calcul de la corrélation
	
	curproj = GLCMfin.cursor()
	correlation = 0
	for(i=0; i<nGL; i++){
		for(j=0; j<nGL; j++){
			curproj.fwd()
			value = curproj.get().get()

			correlation +=  (((i/nGL)-mux)/sigx)*(((j/nGL)-muy)/sigy) * value * dA
		}
	}


	return [contrast, energy, entropy, homogeneity, correlation]
}

def result(title, ROInumber, quantization, distance, contrast, energy, entropy, homogeneity, correlation){
	rt.incrementCounter()
	rt.addLabel(title)
	rt.addValue("ROI number", ROInumber)
	rt.addValue("Distance (in pixels)", distance)
	rt.addValue("Quantization applied", quantization)
	rt.addValue("Contraste", contrast)
	rt.addValue("Energie", energy)
	rt.addValue("Entropie", entropy)
	rt.addValue("Homogénéité", homogeneity)
	rt.addValue("Corrélation", correlation)
	rt.show()
}

main()







void printdir( obj ){
    if( !obj ){
		println( "Object is null\r\n" );
		return;
    }
	if( !obj.metaClass && obj.getClass() ){
        printAllMethods( obj.getClass() );
		return;
    }
	def str = "class ${obj.getClass().name} functions:\r\n";
	obj.metaClass.methods.name.unique().each{
		str += it+"(); ";
	}
	println "${str}\r\n";
}
