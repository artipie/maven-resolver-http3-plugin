## HTTP 3 tools builting

Preparing http3 `curl` client and test `nghttpx` server. Using Linux Ubuntu 22.04 x86_64.
Note that most of prebuilt flavors of curl still don't have http3 support built-in.

```shell
sudo apt install -y build-essential automake libtool pkgconf libev-dev libc-ares-dev
```

### Client

```shell
git clone https://github.com/wolfSSL/wolfssl.git
cd wolfssl
git checkout v5.6.3-stable
autoreconf -fi
./configure --prefix=/usr/local --enable-quic --enable-session-ticket --enable-earlydata --enable-psk --enable-harden
time make -j8
sudo make install

git clone -b v0.14.0 https://github.com/ngtcp2/nghttp3
cd nghttp3
autoreconf -fi
./configure --prefix=/usr/local --enable-lib-only
time make -j8
sudo make install

git clone -b v0.18.0 https://github.com/ngtcp2/ngtcp2
cd ngtcp2
autoreconf -fi
./configure PKG_CONFIG_PATH=/usr/local/lib/pkgconfig:/usr/local/lib/pkgconfig LDFLAGS="-Wl,-rpath,/usr/local/lib" --prefix=/usr/local --enable-lib-only --with-wolfssl
time make -j8
sudo make install

git clone https://github.com/curl/curl
cd curl
git checkout curl-8_2_1
autoreconf -fi
./configure --with-wolfssl=/usr/local --with-nghttp3=/usr/local --with-ngtcp2=/usr/local
time make -j8
sudo make install

sudo ldconfig
```

### Server

```shell
git clone --depth 1 -b openssl-3.0.10+quic https://github.com/quictls/openssl
cd openssl
./config enable-tls1_3 --prefix=/usr/local --libdir=lib
time make -j8
sudo make install

git clone https://github.com/nghttp2/nghttp2.git
cd nghttp2
git checkout v1.55.1
autoreconf -fi
./configure --enable-maintainer-mode --prefix=/usr/local --disable-shared --enable-app --enable-http3 --without-jemalloc --without-libxml2 --without-systemd
time make -j8
sudo make install

sudo ldconfig
```

### Caddy server

1. Download and unpack prebuilt version of Caddy server (e.g. for linux amd64): `https://github.com/caddyserver/caddy/releases`
2. Overriding caddy issue in Linux: https://github.com/quic-go/quic-go/wiki/UDP-Buffer-Sizes
    ```shell
    sudo sysctl -w net.core.rmem_max=2500000
    sudo sysctl -w net.core.wmem_max=2500000
    ```
3. Create Caddy config. Use `stunnel.pem` test certificate from curl build:
    ```shell
    nano Caddyfile
    ```
    ```shell
    {
            https_port 7443
            http_port 8080
    }
    :7443 {
            tls ./stunnel.pem ./stunnel.pem
            reverse_proxy https://repo.maven.apache.org:443 {
                    header_up Host {http.reverse_proxy.upstream.hostport}
            }
            log {
                    output stdout
                    format console
                    level info
            }
    }
    ```
4. Run Caddy server:
    ```shell
    caddy run
    ```

### Testing

```shell
python -m http.server 8080 # http 1.x file server
CERT=$PWD/../curl/tests/stunnel.pem # test certificate from curl build
# http3 proxy server to localhost:8080:
nghttpx $CERT $CERT --backend=localhost,8080 --frontend="localhost,9443;quic"
# http3 proxy server to https://repo.maven.apache.org:
nghttpx $CERT $CERT -L INFO -k --host-rewrite --backend="repo.maven.apache.org,443;/;tls" --frontend="localhost,9443;quic"
# Testing client:
curl -kv --http3 https://nghttp2.org:4433/
curl -kv --http3 https://localhost.org:9433/
time mvn clean package -Daether.connector.https.securityMode=insecure
time mvn -X -e clean package -Dmaven.resolver.transport=native -Daether.connector.https.securityMode=insecure # for full logs
```
