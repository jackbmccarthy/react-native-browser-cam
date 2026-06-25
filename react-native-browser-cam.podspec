require 'json'

package = JSON.parse(File.read(File.join(__dir__, 'package.json')))

Pod::Spec.new do |s|
  s.name         = "react-native-browser-cam"
  s.version      = package['version']
  s.summary      = package['description']
  s.homepage     = package['homepage']
  s.license      = package['license']
  s.author       = package['author']
  s.platforms    = { :ios => "15.1" }
  s.source       = { :git => package['repository']['url'], :tag => s.version }
  
  s.source_files = "ios/**/*.{h,m,mm,swift}"
  
  s.dependency "React-Core"
  s.dependency "HaishinKit", "~> 2.0"
  
  s.frameworks = "AVFoundation", "Photos", "VideoToolbox", "CoreImage", "Metal"
end
