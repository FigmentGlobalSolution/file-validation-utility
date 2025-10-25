# ===== Base JRE 21 on Debian/Ubuntu =====
FROM eclipse-temurin:21-jre-noble

ENV DEBIAN_FRONTEND=noninteractive

# ---------- System deps for AWT/Swing + xvfb + fonts ----------
RUN apt-get update && apt-get install -y --no-install-recommends \
	    ca-certificates \
	    wget \
	    curl \
	    unzip \
	    bash \
	    && rm -rf /var/lib/apt/lists/*

	# ---------- X11 + xvfb ----------
RUN apt-get update && apt-get install -y --no-install-recommends \
	    xvfb \
	    xauth \
	    x11-utils \
	    libx11-6 \
	    libxext6 \
	    libxrender1 \
	    libxtst6 \
	    libxi6 \
	    libxrandr2 \
	    libxcb1 \
	    && rm -rf /var/lib/apt/lists/*

	# ---------- Fonts ----------
RUN apt-get update && apt-get install -y --no-install-recommends \
	    fontconfig \
	    fonts-dejavu \
	    fonts-dejavu-core \
	    fonts-dejavu-extra \
	    fonts-liberation \
	    fonts-liberation2 \
	    libfreetype6 \
	    && rm -rf /var/lib/apt/lists/*

	# Font aliases
RUN mkdir -p /etc/fonts/conf.d && \
	    cat > /etc/fonts/conf.d/60-ms-compatibility-aliases.conf <<-'EOF'
	<?xml version="1.0"?>
	<!DOCTYPE fontconfig SYSTEM "urn:fontconfig:fonts.dtd">
	<fontconfig>
	  <alias>
	    <family>Times New Roman</family>
	    <prefer><family>Liberation Serif</family><family>DejaVu Serif</family></prefer>
	  </alias>
	  <alias>
	    <family>Arial</family>
	    <prefer><family>Liberation Sans</family><family>DejaVu Sans</family></prefer>
	  </alias>
	</fontconfig>
EOF


RUN fc-cache -f -v
	#may need to comment if instllation not proper(for fonts)
RUN set -eux; \
	    apt-get update && \
	    echo "ttf-mscorefonts-installer msttcorefonts/accepted-mscorefonts-eula select true" | debconf-set-selections && \
	    apt-get install -y --no-install-recommends \
	        cabextract \
	        ttf-mscorefonts-installer && \
	    fc-cache -f -v && \
	    rm -rf /var/lib/apt/lists/*

	# Verify
RUN which xvfb-run && xvfb-run -a echo "xvfb works"

# ---------- App layout ----------
# /app/helper.jar         -> your Spring Boot helper JAR
# /app/traces/TRACES-PDF-CONVERTERV2.1L.jar -> TRACES jar
# /app/workdir            -> uploads + outputs live here
WORKDIR /app
RUN mkdir -p /app/output /app/FVU

# Copy the jars in (replace names as needed)
# Put the two jars in the same folder as this Dockerfile before building.
COPY spring-fvu-app-0.0.1-SNAPSHOT.jar /app/fvu-helper.jar
COPY TDS_STANDALONE_FVU_9.2 /app/FVU/TDS_STANDALONE_FVU_9.2

# ---------- Spring Boot config (override-able at runtime) ----------
# Your application.properties already has:
#   traces.workdir=/app/workdir
#   traces.jar.path=/app/traces/TRACES-PDF-CONVERTERV2.1L.jar
# If not, you can set them via env vars below or -D props.
#ENV TRACES_WORKDIR=/app/workdir
#ENV TRACES_JAR_PATH=/app/traces/TRACES-PDF-CONVERTERV2.1L.jar

ENV FVU_OUTPUT_DIR=/app/output
ENV FVU_JAR_PATH=/app/FVU/TDS_STANDALONE_FVU_9.2/TDS_STANDALONE_FVU_9.2.jar

# Expose app port
EXPOSE 8081

# Health and memory options are optional; adjust as appropriate
ENV JAVA_TOOL_OPTIONS="-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0 -Djava.awt.headless=false"

# Run the helper app; your govtJarInvoker already prepends 'xvfb-run -a' when calling the TRACES jar
ENTRYPOINT ["java", "-jar", "/app/fvu-helper.jar"]
