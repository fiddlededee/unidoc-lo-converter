FROM alpine:3.22.5
RUN apk add --no-cache \
    openjdk17

RUN set -xe \
    && apk add --no-cache --purge -uU \
        libreoffice \
        ttf-freefont ttf-opensans ttf-inconsolata \
	    ttf-liberation ttf-dejavu \
        libstdc++ dbus-x11 \
        font-noto-cjk\
    && echo "http://dl-cdn.alpinelinux.org/alpine/edge/main" >> /etc/apk/repositories \
    && echo "http://dl-cdn.alpinelinux.org/alpine/edge/community" >> /etc/apk/repositories \
    && echo "http://dl-cdn.alpinelinux.org/alpine/edge/testing" >> /etc/apk/repositories \
    && apk add --no-cache -U \
	ttf-font-awesome ttf-mononoki ttf-hack \
    && rm -rf /var/cache/apk/* /tmp/*

#If you need other fonts
#COPY ./fonts /usr/share/fonts
#RUN chmod 777 /usr/share/fonts/
#RUN fc-cache -fv


WORKDIR /documents
VOLUME /documents