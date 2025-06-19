import socket
import struct
import sys
import io
import av
import select
from PIL import Image
from PyQt5.QtWidgets import QApplication, QLabel, QWidget, QVBoxLayout
from PyQt5.QtGui import QPixmap, QImage
from PyQt5.QtCore import Qt, QThread, pyqtSignal


class ScreenReceiver(QThread):
    image_received = pyqtSignal(QImage)

    def __init__(self, host='0.0.0.0', port=12345):
        super().__init__()
        self.host = host
        self.port = port
        self.running = True
        self.decoder = av.codec.CodecContext.create('h264', 'r')
        self.got_extradata = False
        self.sps_pps = b''

    def run(self):
        server = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        server.bind((self.host, self.port))
        server.listen(1)
        server.setblocking(False) 
        print(f"Listening on {self.host}:{self.port}...")

        while self.running:
            try:
                ready_to_read, _, _ = select.select([server], [], [], 0.5)
                if ready_to_read:
                    conn, addr = server.accept()
                    self.conn = conn
                    print(f"Connection from {addr}")

                    self.decoder = av.codec.CodecContext.create('h264', 'r')
                    self.got_extradata = False
                    self.sps_pps = b''

                    while self.running:
                        length_data = self.recvall(conn, 4)
                        if not length_data:
                            break
                        length = struct.unpack('>I', length_data)[0]

                        data = self.recvall(conn, length)
                        if not data:
                            break

                        if not self.got_extradata:
                            self.sps_pps += data
                            if len(self.sps_pps) >= 2:
                                self.decoder.extradata = self.sps_pps
                                self.decoder.open()
                                self.got_extradata = True
                            continue

                        packet = av.Packet(data)
                        frames = self.decoder.decode(packet)
                        for frame in frames:
                            img = frame.to_image().convert("RGBA")
                            raw = img.tobytes("raw", "RGBA")
                            qimage = QImage(raw, frame.width, frame.height, QImage.Format_RGBA8888)
                            self.image_received.emit(qimage)

                    try:
                        conn.close()
                    except:
                        pass
            except Exception as e:
                print(f"Connection error: {e}")

        print("Exiting receiver thread...")
        try:
            server.close()
        except:
            pass

    def recvall(self, sock, n):
        data = b''
        while len(data) < n and self.running:
            ready = select.select([sock], [], [], 0.5)
            if ready[0]:
                packet = sock.recv(n - len(data))
                if not packet:
                    return None
                data += packet
        return data if self.running else None

    def stop(self):
        self.running = False
        try:
            if getattr(self, 'conn', None):
                try:
                    self.conn.shutdown(socket.SHUT_RDWR)
                except Exception:
                    pass
                try:
                    self.conn.close()
                except Exception:
                    pass
                self.conn = None
        except Exception as e:
            print(f"Connection close failed: {e}")
        self.wait()


class MainWindow(QWidget):
    def __init__(self):
        super().__init__()
        self.setWindowTitle("Android Screen Receiver")
        self.resize(800, 480)

        self.label = QLabel("Waiting for connection...")
        self.label.setAlignment(Qt.AlignCenter)

        layout = QVBoxLayout()
        layout.addWidget(self.label)
        self.setLayout(layout)

        self.receiver = ScreenReceiver(port=12345)
        self.receiver.image_received.connect(self.update_image)
        self.receiver.start()

    def update_image(self, qimage):
        pixmap = QPixmap.fromImage(qimage)
        self.label.setPixmap(pixmap.scaled(self.label.size(), Qt.KeepAspectRatio))

    def closeEvent(self, event):
        self.receiver.stop()
        event.accept()


if __name__ == "__main__":
    app = QApplication(sys.argv)
    window = MainWindow()
    window.show()
    sys.exit(app.exec_())

